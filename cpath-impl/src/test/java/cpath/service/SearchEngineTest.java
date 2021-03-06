package cpath.service;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

import org.biopax.paxtools.io.SimpleIOHandler;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.level3.Interaction;
import org.biopax.paxtools.model.level3.Pathway;
import org.biopax.paxtools.model.level3.PhysicalEntity;
import org.biopax.paxtools.model.level3.Provenance;
import org.biopax.paxtools.model.level3.SmallMoleculeReference;
import org.junit.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import cpath.config.CPathSettings;
import cpath.service.jaxb.SearchHit;
import cpath.service.jaxb.SearchResponse;

public class SearchEngineTest {
	
	static final ResourceLoader resourceLoader = new DefaultResourceLoader();
	
	final String indexLocation = 
		CPathSettings.getInstance().indexDir() + "_se";

	@Test
	public final void testSearch() throws IOException {
		SimpleIOHandler reader = new SimpleIOHandler();
		Model model = reader.convertFromOWL(resourceLoader
			.getResource("classpath:merge/pathwaydata1.owl").getInputStream());
		SearchEngine searchEngine = new SearchEngine(model, indexLocation);
		searchEngine.index();
		assertTrue(new File(indexLocation).exists());
		
		SearchResponse response = searchEngine.search("ATP", 0, null, null, null);
		assertNotNull(response);
		assertFalse(response.isEmpty());
// System.out.println(response.getSearchHit());
		assertEquals(5, response.getSearchHit().size()); //- only Entity and ER types are indexed
		assertEquals(5, response.getNumHits().intValue());
		
		CPathSettings.getInstance().setDebugEnabled(false);
		response = searchEngine.search("ATP", 0, Interaction.class, null, null);
		assertNotNull(response);
		assertFalse(response.isEmpty());
		assertEquals(2, response.getSearchHit().size());
		//if cPath2 debugging is disabled, - no score/explain in the excerpt
		assertFalse(response.getSearchHit().get(0).getExcerpt().contains("-SCORE-"));
		
		//enable cPath2 debugging...
		CPathSettings.getInstance().setDebugEnabled(true);
		
		response = searchEngine.search("ATP", 0, Pathway.class, null, null);
		assertNotNull(response);
		assertFalse(response.isEmpty());
		assertEquals(1, response.getSearchHit().size());
		
		SearchHit hit = response.getSearchHit().get(0);
		assertEquals(4, hit.getSize().intValue()); //no. member processes, not counting the hit itself
		assertTrue(hit.getExcerpt().contains("-SCORE-"));
		CPathSettings.getInstance().setDebugEnabled(false);
		
		//test a special implementation for wildcard queries
		response = searchEngine.search("*", 0, Pathway.class, null, null);
		assertNotNull(response);
		assertFalse(response.isEmpty());
		assertEquals(1, response.getSearchHit().size());
		
		//find all objects (this here works with page=0 as long as the 
		//total no. objects in the test model < max hits per page)
		response = searchEngine.search("*", 0, null, null, null);
		assertEquals(23, response.getSearchHit().size()); //only Entity and ER types (since 23/12/2015)
			
		response = searchEngine.search("*", 0, PhysicalEntity.class, null, null);
		assertEquals(8, response.getSearchHit().size());
		
		response = searchEngine.search("*", 0, PhysicalEntity.class, null, new String[] {"562"});
		assertEquals(2, response.getSearchHit().size());
		
		response = searchEngine.search("*", 0, PhysicalEntity.class, null, new String[] {"Escherichia"});
		assertFalse(response.isEmpty());
		assertEquals(2, response.getSearchHit().size());
		
		response = searchEngine.search("*", 0, PhysicalEntity.class, null, new String[] {"Escherichia coliü"});
		assertFalse(response.isEmpty());
		assertEquals(2, response.getSearchHit().size());

		// only Entity, ER, Provenance, BioSource types are indexed (since 06/01/2016)
		response = searchEngine.search("*", 0, Provenance.class, null, null);
		assertFalse(response.getSearchHit().isEmpty());
		response = searchEngine.search("*", 0, Provenance.class, null, null);
		assertEquals(2, response.getSearchHit().size());
		response = searchEngine.search("*", 0, Provenance.class, new String[] {"kegg"}, null);
		assertEquals(1, response.getSearchHit().size());
		
		//datasource filter using a URI (required for -update-counts console command and datasources.html page to work)
		response = searchEngine.search("*", 0, Pathway.class, new String[] {"http://identifiers.org/kegg.pathway/"}, null);
		assertFalse(response.isEmpty());
		assertEquals(1, response.getSearchHit().size());
		
		response = searchEngine.search("pathway:glycolysis", 0, SmallMoleculeReference.class, null, null);
		assertEquals(5, response.getSearchHit().size());
		
		//test search with pagination
		searchEngine.setMaxHitsPerPage(10);
		response = searchEngine.search("*", 0, null, null, null);
		assertEquals(0, response.getPageNo().intValue());

		// only Entity, ER, and Provenance types are indexed (since 06/01/2016)
		assertEquals(23, response.getNumHits().intValue());
		assertEquals(10, response.getSearchHit().size());
		response = searchEngine.search("*", 1, null, null, null);
		assertEquals(10, response.getSearchHit().size());
		assertEquals(1, response.getPageNo().intValue());
	}

}
