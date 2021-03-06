package cpath.cleaner;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.biopax.paxtools.controller.ModelUtils;
import org.biopax.paxtools.io.SimpleIOHandler;
import org.biopax.paxtools.model.BioPAXLevel;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.level3.*;
import org.biopax.paxtools.util.ClassFilterSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cpath.service.CPathUtils;
import cpath.service.Cleaner;

import org.biopax.paxtools.model.level3.Process;

/**
 * Implementation of Cleaner interface for Reactome data. 
 * 
 * Can normalize URIs for some Reactome Entity class objects (pathways, interaction)
 * to http://identifiers.org/reactome/R-* form if a unification xref with the stable Reactome ID is found.
 * Removes "unstable" Reactome ID xref from objects where a stable ID is present.
 */
final class ReactomeCleaner implements Cleaner {
    private static Logger log = LoggerFactory.getLogger(ReactomeCleaner.class);

    public void clean(InputStream data, OutputStream cleanedData)
	{	
		// import the original Reactome BioPAX model from file
		SimpleIOHandler simpleReader = new SimpleIOHandler(BioPAXLevel.L3);
		Model model = simpleReader.convertFromOWL(data);
		log.info("Cleaning Reactome data...");

		// Normalize pathway URIs, where possible, using Reactome stable IDs
		// Since v54, Reactome stable ID format has been changed to like: "R-HSA-123456"
		final Map<String, Entity> newUriToEntityMap = new HashMap<String, Entity>();
		final Set<Process> processes = new HashSet<Process>(model.getObjects(Process.class));

		for(Process proc : processes) {
			if (proc.getUri().startsWith("http://identifiers.org/reactome/"))
				continue; //skip for already normalized pathway or interaction

			final Set<UnificationXref> uxrefs = new ClassFilterSet<Xref, UnificationXref>(
					new HashSet<Xref>(proc.getXref()), UnificationXref.class);
			for (UnificationXref x : uxrefs) {
				if (x.getDb() != null && x.getDb().equalsIgnoreCase("Reactome")) {
					String stableId = x.getId();
					//remove 'REACTOME:' (length=9) prefix if present (it's optional - according to MIRIAM)
					if (stableId.startsWith("REACTOME:"))
						stableId = stableId.substring(9);
					// stableID is like 'R-HSA-123456' (or old REACT_12345) now...

					final String uri = "http://identifiers.org/reactome/" + stableId;

					if (!model.containsID(uri) && !newUriToEntityMap.containsKey(uri)) {
						//save it in the map to replace the URI later (see below)
						newUriToEntityMap.put(uri, proc);
					} else { //fix the 'shared unification xref' problem right away
						log.warn("Fixing " + x.getId() + " UX that's shared by several objects: " + x.getXrefOf());
						RelationshipXref rx = BaseCleaner.getOrCreateRx(x, model);
						for (XReferrable owner : new HashSet<XReferrable>(x.getXrefOf())) {
							if (owner.equals(newUriToEntityMap.get(uri)))
								continue; //keep the entity to be updated unchanged
							owner.removeXref(x);
							owner.addXref(rx);
						}
					}
					break; //skip the rest of xrefs (mustn't have multiple 'Reactome' UXs on the same entity)
				}
			}
		}

		// set standard URIs for selected entities;
		for(String uri : newUriToEntityMap.keySet())
			CPathUtils.replaceID(model, newUriToEntityMap.get(uri), uri);
		
		// All Conversions in Reactome are LEFT-TO-RIGH, 
		// unless otherwise was specified (confirmed with Guanming Wu, 2013/12)
		final Set<Conversion> conversions = new HashSet<Conversion>(model.getObjects(Conversion.class));
		for(Conversion ent : conversions) {
			if(ent.getConversionDirection() == null)
				ent.setConversionDirection(ConversionDirectionType.LEFT_TO_RIGHT);
		}
		
		// Remove unstable UnificationXrefs like "Reactome Database ID Release XX"
		// if there is a stable one in the same object
		// Since Reactome v54, stable ID format is different (not like REACT_12345...)
		final Set<Xref> xrefsToRemove = new HashSet<Xref>();
		for(Xref xref: new HashSet<Xref>(model.getObjects(Xref.class))) {
			if(xref.getDb() != null && xref.getDb()
				.toLowerCase().startsWith("reactome database"))
			{
				//remove the long comment (save some RAM)
				if(!(xref instanceof PublicationXref))
					xref.getComment().clear();

				//proceed with a unification xref only...
				if(xref instanceof UnificationXref) {
					for(XReferrable owner :  new HashSet<XReferrable>(xref.getXrefOf())) {
						for(Xref x : new HashSet<Xref>(owner.getXref())) {
							if(!(x instanceof UnificationXref) || x.equals(xref))
								continue;
							//another unif. xref present in the same owner object
							if(x.getDb() != null && x.getDb().equalsIgnoreCase("reactome")) {
								//remove the unstable ID ref from the object that has a stable id
								owner.removeXref(xref);
								xrefsToRemove.add(xref);
							}
						}
					}
				}
			}
		}
		log.info(xrefsToRemove.size() + " unstable unif. xrefs, where a stable one also exists, " +
			"were removed from the corresponding xref properties.");
		
		ModelUtils.removeObjectsIfDangling(model, UtilityClass.class);
		
		// convert model back to OutputStream for return
		try {
			simpleReader.convertToOWL(model, cleanedData);
		} catch (Exception e) {
			throw new RuntimeException("clean(), Exception thrown while saving cleaned Reactome data", e);
		}
	}

}
