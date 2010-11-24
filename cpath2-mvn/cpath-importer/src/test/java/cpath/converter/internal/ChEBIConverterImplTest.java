package cpath.converter.internal;

// imports
import cpath.converter.Converter;

import org.biopax.paxtools.controller.SimpleMerger;
import org.biopax.paxtools.impl.ModelImpl;
import org.biopax.paxtools.io.simpleIO.SimpleEditorMap;
import org.biopax.paxtools.io.simpleIO.SimpleExporter;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.BioPAXLevel;
import org.biopax.paxtools.model.level3.*;

import org.junit.Test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;


/**
 * Test Chebi to BioPAX converter.
 *
 */
public class ChEBIConverterImplTest {

	/**
	 * Test method for {@link cpath.converter.internal.ChEBIConverterImpl#convert(java.io.InputStream)}.
	 * @throws IOException 
	 */
	@Test
	public void testConvert() throws IOException {
	
		// convert test data
		InputStream is = this.getClass().getClassLoader().getResourceAsStream("test_chebi_data.dat");
		//Model model = BioPAXLevel.L3.getDefaultFactory().createModel();
		// extend Model for the converter the calls 'merge' method to work
		Model model = new ModelImpl(BioPAXLevel.L3.getDefaultFactory()) {
			/* (non-Javadoc)
			 * @see org.biopax.paxtools.impl.ModelImpl#merge(org.biopax.paxtools.model.Model)
			 */
			@Override
			public void merge(Model source) {
				SimpleMerger simpleMerger = new SimpleMerger(new SimpleEditorMap(getLevel()));
				simpleMerger.merge(this, source);
			}
		};

		// setup the converter
		Converter converter = new ChEBIConverterForTestingImpl(model);
		converter.convert(is);
		
		// dump owl for review
		String outFilename = getClass().getClassLoader().getResource("").getPath() 
			+ File.separator + "testConvertChebi.out.owl";
		(new SimpleExporter(BioPAXLevel.L3)).convertToOWL(model, 
				new FileOutputStream(outFilename));

		// get all small molecule references out
		assertEquals(3, model.getObjects(SmallMoleculeReference.class).size());

		// get lactic acid sm
		String rdfID = "urn:miriam:chebi:422";
		assertTrue(model.containsID(rdfID));
		SmallMoleculeReference smallMoleculeReference = (SmallMoleculeReference)model.getByID(rdfID);

		// check some props
		assertTrue(smallMoleculeReference.getDisplayName().equals("(S)-lactic acid"));
		assertTrue(smallMoleculeReference.getName().size() == 10);
		assertTrue(smallMoleculeReference.getChemicalFormula().equals("C3H6O3"));
		int relationshipXrefCount = 0;
		int unificationXrefCount = 0;
		for (Xref xref : smallMoleculeReference.getXref()) {
			if (xref instanceof RelationshipXref) ++relationshipXrefCount;
			if (xref instanceof UnificationXref) ++ unificationXrefCount;
		}
		assertEquals(3, unificationXrefCount);
		assertEquals(12, relationshipXrefCount);
		
		// following checks work in this test only (using in-memory model); with DAO - use getObject...
        assertTrue(model.containsID("urn:miriam:chebi:20"));
        EntityReference er20 = (EntityReference) model.getByID("urn:miriam:chebi:20");
        assertTrue(model.containsID("urn:miriam:chebi:28"));
        EntityReference er28 = (EntityReference) model.getByID("urn:miriam:chebi:28");
        assertTrue(model.containsID("urn:miriam:chebi:422"));
        EntityReference er422 = (EntityReference) model.getByID("urn:miriam:chebi:422");
        
        assertTrue(er20.getMemberEntityReferenceOf().contains(er422));
        assertEquals(er20, er422.getMemberEntityReference().iterator().next());
        
		assertTrue(er422.getMemberEntityReferenceOf().contains(er28));
        assertEquals(er422, er28.getMemberEntityReference().iterator().next());
        
        assertTrue(model.containsID("urn:pathwaycommons:RelationshipXref:HAS_PART_CHEBI_20"));
        assertTrue(model.containsID("urn:pathwaycommons:RelationshipXref:IS_CONJUGATE_ACID_OF_CHEBI_422"));
	}
}
