package cpath.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.biopax.paxtools.impl.BioPAXElementImpl;
import org.biopax.paxtools.io.SimpleIOHandler;
import org.biopax.paxtools.model.BioPAXElement;
import org.biopax.paxtools.model.BioPAXLevel;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.level3.RelationshipTypeVocabulary;
import org.biopax.paxtools.model.level3.RelationshipXref;
import org.biopax.paxtools.model.level3.UnificationXref;
import org.biopax.paxtools.normalizer.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;

import cpath.config.CPathSettings;
import cpath.jpa.Content;
import cpath.jpa.Metadata;

import static cpath.jpa.Metadata.*;

/**
 * @author rodche
 *
 */
public final class CPathUtils {
	// logger
    private static Logger LOGGER = LoggerFactory.getLogger(CPathUtils.class);
    
	// LOADER can handle file://, ftp://, http://  PROVIDER_URL resources
	public static final ResourceLoader LOADER = new DefaultResourceLoader();
		
	
	private CPathUtils() {
		throw new AssertionError("Not instantiable");
	}

    
    /**
     * Empties the directory.
     * 
     * @param path a directory
     * @return
     */
    public static void cleanupDirectory(File path) {
        if( path.exists() && path.isDirectory()) {
          File[] files = path.listFiles();
          for(int i=0; i<files.length; i++) {
             if(files[i].isDirectory()) {
            	 cleanupDirectory(files[i]);
             }
             else {
               files[i].delete();
             }
          }
        } else {
			path.mkdir();
		}
    }
    
    
    /**
     *  For the given url, returns a collection of Metadata Objects.
     *
     * @param url String
     * @return Collection<Metadata>
     */
    public static Collection<Metadata> readMetadata(final String url)
    {
        // order of lines/records in the Metadata table does matter (since 2013/03);
		// so List is used here instead of HashSet
		List<Metadata> toReturn = new ArrayList<Metadata>();

        // check args
        if (url == null) {
            throw new IllegalArgumentException("url must not be null");
        }

        // get data from service
        BufferedReader reader = null;
        try {
            // we'd like to read lines at a time
            reader = new BufferedReader(new InputStreamReader(
            	LOADER.getResource(url).getInputStream(), "UTF-8"));

            // are we ready to read?
            while (reader.ready()) 
            {
                // grab a line
                String line = reader.readLine();
                if("".equals(line.trim()))
                	continue;
                else if(line.trim().startsWith("#")) {
					LOGGER.info("readMetadata(), ignored line: " + line);
                	continue; //ignore/skip parsing
                }
                	
                /* for now, assume line is delimited into 9 columns by '\t' (tab);
                 * empty strings in the middle (the result of using \t\t) and 
                 * trailing empty string after the last tabulation (i.e., Converter 
                 * class name, if any), will be added to the tokens array as well.
                 */
                String[] tokens = line.split("\t",-1);
                
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("readMetadata(), token size: " + tokens.length);
					for (String token : tokens) {
						LOGGER.debug("readMetadata(), token: " + token);
					}
				}

                assert tokens.length == NUMBER_METADATA_ITEMS : "readMetadata(): " +
                		"wrong number of columns, " + tokens.length + " instead of "
                		+ NUMBER_METADATA_ITEMS + ", in the metadata record: " + line;

				// get metadata type
				Metadata.METADATA_TYPE metadataType = Metadata.METADATA_TYPE.valueOf(tokens[METADATA_TYPE_INDEX]);
				
				LOGGER.debug("readMetadata(): make a Metadata bean.");

                // create a metadata bean
                Metadata metadata = new Metadata(
                		tokens[METADATA_IDENTIFIER_INDEX], 
                		tokens[METADATA_NAME_INDEX],
                		tokens[METADATA_DESCRIPTION_INDEX], 
                		tokens[METADATA_DATA_URL_INDEX],
                        tokens[METADATA_HOMEPAGE_URL_INDEX], 
                        tokens[METADATA_ICON_URL_INDEX],
                        metadataType,
						tokens[METADATA_CLEANER_CLASS_NAME_INDEX],
						tokens[METADATA_CONVERTER_CLASS_NAME_INDEX],
						tokens[METADATA_PUBMEDID_INDEX],		
                		tokens[METADATA_AVAILABILITY_INDEX]);
                
				if (LOGGER.isInfoEnabled()) {
					LOGGER.info("readMetadata(): adding Metadata: "
					+ "identifier=" + metadata.getIdentifier() 
					+ "; name=" + metadata.getName()
					+ "; date/comment=" + metadata.getDescription()
					+ "; location=" + metadata.getUrlToData()
					+ "; icon=" + metadata.getIconUrl()
					+ "; type=" + metadata.getType()
					+ "; cleaner=" + metadata.getCleanerClassname() 
					+ "; converter=" + metadata.getConverterClassname()
					+ "; pubmedId=" + metadata.getPubmedId() 
					+ "; availability=" + metadata.getAvailability()
					);
				}
					
				// add metadata object toc collection we return
				toReturn.add(metadata);
            } 
        } catch (java.io.UnsupportedEncodingException e) {
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            closeQuietly(reader);
        }

        return toReturn;
    }

	
    /**
     * For the given Metadata, unpacks and reads the corresponding 
     * original zip data archive, creating new {@link Content} objects 
     * in the metadata's dataFile collection.
     * Skips for system files/directory entries.
     *
     * @see Metadata#getDataArchiveName()
	 * @param metadata Metadata
     * @throws RuntimeException if an IO error occurs
     */
    public static void analyzeAndOrganizeContent(final Metadata metadata) 
    {
		Collection<Content> contentCollection = new HashSet<Content>();
		ZipInputStream zis = null;
		try {
			zis = new ZipInputStream((metadata.getUrlToData()
				.startsWith("classpath:")) //a hack for easy junit tests
					? LOADER.getResource(metadata.getUrlToData()).getInputStream() 
						: new FileInputStream(metadata.getDataArchiveName()));		
			// interate over zip entries
			ZipEntry entry = null;
            while ((entry = zis.getNextEntry()) != null) 
            {
            	String entryName = entry.getName();
           		LOGGER.info("analyzeAndOrganizeContent(), processing zip entry: " + entryName);

				//skip some sys/tmp files (that MacOSX creates sometimes)
				if(entry.isDirectory() || entryName.contains("__MACOSX") 
						|| entryName.startsWith(".") || entryName.contains("/.") 
						|| entryName.contains("\\.")) 
				{
            		LOGGER.info("analyzeAndOrganizeContent(), skipped " + entryName);
					continue;
				}
				
				// create pathway data object
				LOGGER.info("analyzeAndOrganizeContent(), adding new Content: " 
					+ entryName + " of " + metadata.getIdentifier());
				Content content = new Content(metadata, entryName);
				// add object to return collection
				contentCollection.add(content);
				
				OutputStream gzos = new GZIPOutputStream(new FileOutputStream(content.originalFile()));
				copy(zis, gzos);
				gzos.close();
            }           
		} catch (IOException e) {
			throw new RuntimeException("analyzeAndOrganizeContent(), " +
					"failed reading from: " + metadata.getIdentifier() , e);
		} finally {
			closeQuietly(zis);
		}
		
		if(contentCollection != null && !contentCollection.isEmpty())
			metadata.getContent().addAll(contentCollection);
		else
			LOGGER.warn("analyzeAndOrganizeContent(), no data found for " + metadata);
    }

    
    /**
     * Uncompresses the zip input stream,
     * all entries if it's a multi-entry archive,
     * and writes to the output stream.
     * 
     * Skips for system files/directory entries.
     * 
     * Does not close the streams.
     * 
     * @param zis zip input stream
     * @param os output stream
     */
    public static void unzip(ZipInputStream zis, OutputStream os) 
    {
		try {		
			// interate over zip entries
			ZipEntry entry = null;
            while ((entry = zis.getNextEntry()) != null) 
            {
            	String entryName = entry.getName();
				//skip some sys/tmp files (that MacOSX creates sometimes)
				if(entry.isDirectory() || entryName.contains("__MACOSX") 
						|| entryName.startsWith(".") || entryName.contains("/.") 
						|| entryName.contains("\\.")) 
				{
					continue;
				}
				
				copy(zis, os); //does not close os
            }           
		} catch (IOException e) {
			throw new RuntimeException("unzip(), failed", e);
		} finally {
			closeQuietly(os);
		}
    }

    
   /**
    * Close the InputStream quietly.
    * @param is
    */
    private static void closeQuietly(final InputStream is) {
    	try{is.close();}catch(Exception e){LOGGER.warn("is.close() failed." + e);}
    }

    /**
     * Close the OutputStream quietly.
     * @param os
     */
     private static void closeQuietly(final OutputStream os) {
         try{os.close();}catch(Exception e){LOGGER.warn("os.close() failed." + e);}
     }
    
   /**
    * Close the reader quietly.
    * @param reader
    */
    private static void closeQuietly(final Reader reader) {
    	try{reader.close();}catch(Exception e){LOGGER.warn("reader.close() failed." + e);}
    }
       
    
    /**
     * Writes or overwrites from the array to target file.
     * @param src
     * @param file
     * @throws RuntimeException when there was an IO problem
     */
    public static void write(byte[] src, String file) {
    	FileOutputStream os = null;
    	try {
    		os = new FileOutputStream(file);
    		os.write(src);
    		os.flush();
    	} catch (IOException e) {
    		throw new RuntimeException("write: failed writing byte[] to " 
    			+ " to " + file, e);
    	} finally {closeQuietly(os);}
    }


	/**
	 * Replaces the URI of a BioPAX object
	 * using java reflection. Normally, one should avoid this;
	 * please use when absolutely necessary and with great care. 
	 * 
	 * @param model model
	 * @param el biopax object
	 * @param newUri URI
	 */
	public static  void replaceID(Model model, BioPAXElement el, String newUri) {
		if(el.getUri().equals(newUri))
			return; // no action required
		
		model.remove(el);
		try {
			Method m = BioPAXElementImpl.class.getDeclaredMethod("setUri", String.class);
			m.setAccessible(true);
			m.invoke(el, newUri);
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		model.add(el);
	}
	
	
	/**
	 * Loads the BioPAX model from a Gzip archive 
	 * previously created by the same cpath2 instance.
	 * 
	 * @param archive
	 * @return big BioPAX model
	 */
	static Model importFromTheArchive(String archive) {
		
		Model model = null;

		try {
			LOGGER.info("Loading the BioPAX Model from " + archive);
			model = (new SimpleIOHandler(BioPAXLevel.L3))
					.convertFromOWL(new GZIPInputStream(new FileInputStream(archive)));
		} 
		catch (IOException e) {
			LOGGER.error("Failed to import model from '" + archive + "' - " + e);
		}

		return model;
	}
	
	
	/**
	 * Downloads a file (content) from a URI
	 * and saves in the cpath2 home directory. The content
	 * can be anything, but only single-file GZIP archives 
	 * can be optionally expanded with this method, before saved
	 * (e.g., this is how we grab GeoIP GeoLiteCity database).
	 * 
	 * @param srcUrl remote URL
	 * @param destFile name or relative path and name
	 * @param unpack if true, expands the archive
	 * @param replace
	 * @return bytes saved or 0 if existed before file weren't replaced
	 * @throws RuntimeException when there was an IOException
	 */
	public static long download(String srcUrl, String destFile, 
			boolean unpack, boolean replace) {
		
		File localFile = new File(destFile);
		
		if(localFile.exists() && !replace) {
			LOGGER.info("Keep existing " + destFile);
			return 0L;
		}
		
		Resource resource = LOADER.getResource(srcUrl);
        long size = 0; 
        
        try {
        
        if(resource.isReadable()) {
        	size = resource.contentLength();
        	LOGGER.info(srcUrl + " content length= " + size);
        }       
        if(size < 0) 
        	size = 100 * 1024 * 1024 * 1024;
        
        //downoad to a tmp file
        ReadableByteChannel source = Channels.newChannel(resource.getInputStream());      
       	File tmpf = File.createTempFile("cpath2_", ".download");
       	tmpf.deleteOnExit();
        FileOutputStream dest = new FileOutputStream(tmpf);        
        size = dest.getChannel().transferFrom(source, 0, size);
        dest.close();
        LOGGER.info(size + " bytes downloaded from " + srcUrl);
        
        if(unpack) {
        	GZIPInputStream ginstream = new GZIPInputStream(new FileInputStream(tmpf));
        	FileOutputStream outstream = new FileOutputStream(localFile);
        	byte[] buf = new byte[1024]; 
        	int len;
        	while ((len = ginstream.read(buf)) > 0) 
        		outstream.write(buf, 0, len);
        	ginstream.close();
        	outstream.close();
        } else {
        	if(replace)
        		if(localFile.exists() && !localFile.delete())
            		throw new RuntimeException("Failed to delete old " 
            			+ localFile.getAbsolutePath());
        	if(!tmpf.renameTo(localFile))
        		throw new RuntimeException("Failed to move " 
        			+ tmpf.getAbsolutePath() + " to " + localFile.getAbsolutePath());
        }
        
        } catch(IOException e) {
        	throw new RuntimeException("download(). failed", e);
        }
              
        return size;
	}
	

	/**
	 * Reads from the input and writes to the output stream
	 * 
	 * @param is
	 * @param os
	 * @throws IOException
	 */
	public static void copy(InputStream is, OutputStream os) throws IOException {		
		IOUtils.copy(is, os);
		os.flush();
		//do not close streams	(can be re-used outside)
	}	

	
	/**
	 * For a warehouse (normalized) EntityReference's or CV's URI 
	 * gets the corresponding identifier (e.g., UniProt or ChEBI primary ID).
	 * (this depends on current biopax normalizer and cpath2 premerge
	 * that make/consume 'http://identifiers.org/*' URIs for those utility class biopax objects.)
	 * 
	 * @param uri URI
	 * @return local part URI - ID
	 */
	public static String idfromNormalizedUri(String uri) {
		Assert.isTrue(uri.contains("http://identifiers.org/"));
		return uri.substring(uri.lastIndexOf('/')+1);
	}
	
	
	/**
	 * Imports the Main BioPAX Model from the merged all-in-one BioPAX archive.
	 * 
	 * @return model
	 */
	public static Model loadMainBiopaxModel()  {
		return importFromTheArchive(CPathSettings.getInstance().mainModelFile());
	}

	
	/**
	 * Builds the BioPAX Paxtools model from 
	 * the warehouse BioPAX archive.
	 * 
	 * @return model
	 */
	public static Model loadWarehouseBiopaxModel() {
		return importFromTheArchive(CPathSettings.getInstance().warehouseModelFile());
	}


	/**
	 * Imports the specified data source BioPAX model from the corresponding merged BioPAX archive.
	 *
	 * @return model
	 */
	public static Model loadBiopaxModelByDatasource(Metadata datasource) {
		File in = new File(CPathSettings.getInstance().biopaxFileNameFull(datasource.getIdentifier()));
		if (in.exists()) {
			return importFromTheArchive(in.getPath());
		} else {
			LOGGER.debug("loadBiopaxModelByDatasource, file not found: " + in.getPath()
					+ " (not merged yet, or file was deleted)");
			return null;
		}
	}


	/**
	 * Auto-fix an ID of particular type before using it
	 * for id-mapping. This helps mapping e.g., RefSeq versions ID and
	 * UniProt isoforms to primary UniProt accessions despite our id-mapping db
	 * does not have such records as e.g. "NP_12345.1 maps to P01234".
	 *
	 * @param fromDb type of the identifier (standard resource name, e.g., RefSeq)
	 * @param fromId identifier
     * @return "fixed" ID
     */
	public static String fixSourceIdForMapping(String fromDb, String fromId) {
		Assert.hasText(fromId);
		Assert.hasText(fromDb);

		String id = fromId;
		String db = fromDb.toUpperCase();

		if(db.startsWith("UNIPROT") || db.contains("SWISSPROT") || db.contains("TREMBL")) {
			//always use UniProt ID instead of the isoform ID for mapping
			if(id.contains("-"))
				id = id.replaceFirst("-\\d+$", "");
		}
		else if(db.equals("REFSEQ") && id.contains(".")) {
			//strip, e.g., refseq:NP_012345.2 to refseq:NP_012345
			id = id.replaceFirst("\\.\\d+$", "");
		}
		else if(db.startsWith("KEGG") && id.matches(":\\d+$")) {
			id = id.substring(id.lastIndexOf(':') + 1); //it's NCBI Gene ID;
		}
		else if(db.contains("PUBCHEM") && (db.contains("SUBSTANCE") || db.contains("SID"))) {
			id = id.toUpperCase(); //ok for a SID
			//add prefix if not present
			if(!id.startsWith("SID:") && id.matches("^\\d+$"))
				id = "SID:" + id;
		}
		else if(db.contains("PUBCHEM") && (db.contains("COMPOUND") || db.contains("CID"))) {
			id = id.toUpperCase(); //ok for a CID
			//add prefix if not present
			if(!id.startsWith("CID:") && id.matches("^\\d+$"))
				id = "CID:" + id;
		}

		return id;
	}

	/**
	 * Whether a string starts with any of the prefixes (case insensitive).
	 * @param s to search in
	 * @param prefixes search terms
     * @return
     */
	public static boolean startsWithAnyIgnoreCase(String s, Collection<String> prefixes){
		for (String prefix : prefixes){
			if (StringUtils.startsWithIgnoreCase(s, prefix)){
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a string starts with any of the prefixes (case insensitive).
	 * @param s
	 * @param prefixes
     * @return
     */
	public static boolean startsWithAnyIgnoreCase(String s, String... prefixes){
		return startsWithAnyIgnoreCase(s, Arrays.asList(prefixes));
	}

	/**
	 * Given relationship type CV 'term' and target biological 'db' and 'id',
	 * finds or creates a new relationship xref (and its controlled vocabulary) in the model.
	 *
	 * Note: the corresponding CV does not have a unification xref
	 * (this method won't validate; so, non-standard CV terms can be used).
	 *
	 * @param vocab relationship xref type
	 * @param model a biopax model where to find/add the xref
	 */
	public static RelationshipXref findOrCreateRelationshipXref(
			RelTypeVocab vocab, String db, String id, Model model)
	{
		Assert.notNull(vocab);

		RelationshipXref toReturn = null;

		String uri = Normalizer.uri(model.getXmlBase(), db, id + "_" + vocab.toString(), RelationshipXref.class);
		if (model.containsID(uri)) {
			return (RelationshipXref) model.getByID(uri);
		}

		// create a new relationship xref
		toReturn = model.addNew(RelationshipXref.class, uri);
		toReturn.setDb(db.toLowerCase());
		toReturn.setId(id);

		// create/add the relationship type vocabulary
		String relTypeCvUri = vocab.uri; //identifiers.org standard URI
		RelationshipTypeVocabulary rtv = (RelationshipTypeVocabulary) model.getByID(relTypeCvUri);
		if (rtv == null) {
			rtv = model.addNew(RelationshipTypeVocabulary.class, relTypeCvUri);
			rtv.addTerm(vocab.term);
			//add the unif.xref
			uri = Normalizer.uri(model.getXmlBase(), vocab.db, vocab.id, UnificationXref.class);
			UnificationXref rtvux = (UnificationXref) model.getByID(uri);
			if (rtvux == null) {
				rtvux = model.addNew(UnificationXref.class, uri);
				rtvux.setDb(vocab.db.toLowerCase());
				rtvux.setId(vocab.id);
			}
			rtv.addXref(rtvux);
		}
		toReturn.setRelationshipType(rtv);

		return toReturn;
	}
}
