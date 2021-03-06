package cpath.service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import cpath.config.CPathSettings;
import org.biopax.paxtools.io.gsea.GSEAConverter;
import org.biopax.paxtools.io.jsonld.JsonldBiopaxConverter;
import org.biopax.paxtools.io.jsonld.JsonldConverter;
import org.biopax.paxtools.io.sbgn.L3ToSBGNPDConverter;
import org.biopax.paxtools.io.sbgn.ListUbiqueDetector;
import org.biopax.paxtools.io.*;
import org.biopax.paxtools.model.*;
import org.biopax.paxtools.model.level3.Provenance;
import org.biopax.paxtools.pattern.miner.*;
import org.biopax.paxtools.pattern.util.Blacklist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpath.service.jaxb.DataResponse;
import cpath.service.jaxb.ServiceResponse;
import static cpath.service.Status.*;

/**
 * A utility class to convert a BioPAX 
 * L3 RDF/XML data stream or {@link Model} 
 * to one of {@link OutputFormat}s  
 * (including - to BioPAX L3 RDF/XML)
 * 
 * @author rodche
 */
public class BiopaxConverter {
	private static final Logger log = LoggerFactory.getLogger(BiopaxConverter.class);
	
	private final Blacklist blacklist;
		
	/**
	 * Constructor.
	 * 
	 * @param blacklist of ubiquitous molecules to exclude (in some algorithms)
	 */
	public BiopaxConverter(Blacklist blacklist)
	{
		this.blacklist = blacklist;
	}

 
	/**
     * Converts the BioPAX data into the other format.
     * 
     * @param m paxtools model
     * @param format output format
     * @param os output stream
     * @param args optional format-specific parameters
	 * @throws IOException when an error occurs while writing to the output stream
     */
    private void convert(Model m, OutputFormat format, OutputStream os, Object... args)
    		throws IOException 
    {
			switch (format) {
			case BIOPAX: //to OWL (RDF/XML)
				new SimpleIOHandler().convertToOWL(m, os);
				break;
			case BINARY_SIF:
			case SIF:
				String db = "hgnc symbol"; //default
				if (args.length > 0 && args[0] instanceof String)
					db = (String) args[0];
				convertToSIF(m, os, false, db);
				break;
			case EXTENDED_BINARY_SIF:
			case TXT:
				db = "hgnc symbol";
				if (args.length > 0 && args[0] instanceof String)
					db = (String) args[0];
				convertToSIF(m, os, true, db);
				break;
			case GSEA:
				db = "uniprot"; //default
				if (args.length > 0 && args[0] instanceof String)
					db = ((String) args[0]).trim().toLowerCase();
				boolean skipOutsidePathways = true;
				if (args.length > 1) {
					skipOutsidePathways = (args[1] instanceof Boolean)
						? ((Boolean)args[1]).booleanValue()
							: Boolean.parseBoolean(String.valueOf(args[1]));
				}
				convertToGSEA(m, os, db, skipOutsidePathways);
				break;
            case SBGN:
				boolean doLayout = true;
				if (args.length > 0) {
					doLayout = (args[0] instanceof Boolean)
							? ((Boolean)args[0]).booleanValue() 
								: Boolean.parseBoolean(String.valueOf(args[0]));
				}
                convertToSBGN(m, os, blacklist, doLayout);
                break;
			case JSONLD:
				convertToJsonLd(m, os);
				break;
			case JSON:
				convertToCyJson(m, os);
				break;
			default: throw new UnsupportedOperationException(
					"convert, yet unsupported format: " + format);
			}
    }

	private void convertToCyJson(Model m, OutputStream os) {
		//TODO implement
	}

	private void convertToJsonLd(Model m, OutputStream os) throws IOException {
		DataResponse dr = (DataResponse) convert(m, OutputFormat.BIOPAX);
		JsonldConverter converter = new JsonldBiopaxConverter();
		Path inp = (Path) dr.getData();
		converter.convertToJsonld(new FileInputStream(inp.toFile()), os);
		inp.toFile().delete();
	}


	/**
     * Converts not too large BioPAX model 
     * (e.g., a graph query result) to another format.
     * 
     * @param m a sub-model (not too large), e.g., a get/graph query result
     * @param format output format
     * @param args optional format-specific parameters
     * @return data response with the converted data (up to 1Gb utf-8 string) or {@link ErrorResponse}.
     */
    public ServiceResponse convert(Model m, OutputFormat format, Object... args)
    {
    	if(m == null || m.getObjects().isEmpty()) {
			return new ErrorResponse(NO_RESULTS_FOUND, "Empty BioPAX Model");
		}
    	
		// otherwise, convert, return a new DataResponse
    	// (can contain up to ~ 1Gb unicode string data)
    	// a TMP File is used instead of a byte array; set the file path as dataResponse.data value
    	File tmpFile = null;
		try {
    		Path tmpFilePath = Files.createTempFile("cpath2", format.getExt());
    		tmpFile = tmpFilePath.toFile();
    		tmpFile.deleteOnExit();
        	OutputStream os = new FileOutputStream(tmpFile);
    		convert(m, format, os, args); //os gets auto-closed there		
    		DataResponse dataResponse = new DataResponse();
			dataResponse.setFormat(format);
			dataResponse.setData(tmpFilePath);
			// extract and save data provider names
			dataResponse.setProviders(providers(m));			
			return dataResponse;
		}
        catch (Exception e) {
        	if(tmpFile != null)
        		tmpFile.delete();
        	return new ErrorResponse(INTERNAL_ERROR, e);
		}
    }


    /**
     * Converts a BioPAX Model to SBGN format.
     *
     * @param m BioPAX object model to convert
     * @param stream output stream for the SBGN-ML result
     * @param blackList skip-list of ubiquitous small molecules
     * @param doLayout whether to apply the default layout or not
     * 
     * @throws IOException when there is an output stream writing error
     */
    private void convertToSBGN(Model m, OutputStream stream, Blacklist blackList, boolean doLayout)
		throws IOException
	{
    	
    	L3ToSBGNPDConverter converter = new L3ToSBGNPDConverter(
			new ListUbiqueDetector(blackList.getListed()), null, doLayout);

		converter.writeSBGN(m, stream);
    }


    /**
	 * Converts service results that contain 
	 * a not empty BioPAX Model to GSEA format.
	 * 
     * @param m paxtools model
     * @param stream output stream
	 * @param outputIdType output identifiers type (db name, is data-specific, the default is UniProt)
	 * @param skipOutsidePathways if true - won't write ID sets that relate to no pathway
	 * @throws IOException when there is an output stream writing error
	 */
	private void convertToGSEA(Model m, OutputStream stream, String outputIdType, boolean skipOutsidePathways)
			throws IOException 
	{	
		if(outputIdType==null || outputIdType.isEmpty())
			outputIdType = "uniprot";

		// convert (make per pathway entries; won't traverse into sub-pathways of a pathway; only pre-selected organisms)
		GSEAConverter gseaConverter = new GSEAConverter(outputIdType, true, true);
		Set<String> allowedTaxIds = CPathSettings.getInstance().getOrganismTaxonomyIds();
		gseaConverter.setAllowedOrganisms(allowedTaxIds);
		gseaConverter.setSkipOutsidePathways(skipOutsidePathways);
		gseaConverter.writeToGSEA(m, stream);
	}

	
	/**
	 * Converts a not empty BioPAX Model (contained in the service bean) 
	 * to the SIF or <strong>single-file</strong> extended SIF format.
	 * 
	 * This method is primarily designed for the web service.
	 * 
     * @param m biopax paxtools to convert
     * @param out stream
     * @param extended if true, calls SIFNX else - SIF
	 * @param db - either 'uniprot', 'hgnc', etc.; if null - 'HGNC symbol' is the default.
	 * 
	 * @throws IOException when there is an output stream writing error
	 */
	private void convertToSIF(Model m, OutputStream out, boolean extended, String db)
			throws IOException 
	{
		ConfigurableIDFetcher idFetcher = new ConfigurableIDFetcher();
		idFetcher.chemDbStartsWithOrEquals("chebi");

		if(db == null || db.isEmpty() || db.toLowerCase().startsWith("hgnc")) {
			idFetcher.seqDbStartsWithOrEquals("hgnc");
		}
		else if(db.toLowerCase().startsWith("uniprot")) {
			idFetcher.seqDbStartsWithOrEquals("uniprot");
		} else {
			idFetcher.seqDbStartsWithOrEquals(db);
		}

		final Collection<SIFType> sifTypes = new HashSet<SIFType>(Arrays.asList(SIFEnum.values()));
		sifTypes.remove(SIFEnum.NEIGHBOR_OF); //exclude NEIGHBOR_OF
		SIFSearcher searcher = new SIFSearcher(idFetcher, sifTypes.toArray(new SIFType[sifTypes.size()]));
		searcher.setBlacklist(blacklist);

		if(extended) {
			Set<SIFInteraction> binaryInts = searcher.searchSIF(m);
			ExtendedSIFWriter.write(binaryInts, out);
		} else {
			searcher.searchSIF(m, out);
		}
	}
	
	/**
	 * The list of datasources (data providers)
	 * the BioPAX model contains.
	 * 
	 * @param m BioPAX object model
	 */
	@SuppressWarnings("unchecked")
	private Set<String> providers(Model m) {
		Set<String> names = null;
		
		if(m != null) {
			Set<Provenance> provs = m.getObjects(Provenance.class);		
			if(provs!= null && !provs.isEmpty()) {
				names = new TreeSet<String>();
				for(Provenance prov : provs) {
					String name = prov.getStandardName();
					if(name != null)
						names.add(name);
					else {
						name = prov.getDisplayName();
						if(name != null)
							names.add(name);
						else 
							log.warn("No standard|display name found for " + prov);
					}
				}
			}
		}
		
		return (names != null && !names.isEmpty()) 
				? names : Collections.EMPTY_SET;
	}
}
