/**
 * 
 */
package org.brisskit.onyxexport;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;

import org.brisskit.export.metadata.config.beans.*;
import org.brisskit.onyxdata.beans.*;
import org.brisskit.onyxentities.beans.* ;


/**
 * @author jeff
 *
 */
public class ParticipantCompositor {
	
	public void setExportDirectory( File exportDirectory ) {
		this.exportDirectory = exportDirectory;
	}

	public void setConfig( OnyxExportConfigDocument config ) {
		this.config = config ;
	}
	
	public CompositionPhaseType getCompositionPhase() {
		return this.config.getOnyxExportConfig().getCompositionPhase() ;
	}

	private static Log log = LogFactory.getLog( ParticipantCompositor.class ) ;
	
	private static final String USAGE =
	        "Usage: ParticipantCompositor {Parameters}\n" +       
	                "Parameters:\n" +
	                " -export=path-to-onyx-export-directory\n" +
	                " -config=path-to-config-file\n" +
	                "Notes:\n" +
	                " (1) All parameters are mandatory.\n" +
	                " (2) Parameter triggers can be shortened to the first letter; ie: -e,-c.\n" +
	                " (3) The export path must point to an expanded Onyx export file where the XML\n" +
	                "     files have been updated with the appropriate name space." ;
	
	private static StringBuffer logIndent = null ;
	
	private File exportDirectory ;
	private OnyxExportConfigDocument config ;
	private ArrayList<ParticipantCompositor.Participant> participants ;
	private ArrayList<MatchedPair> matchedPairs ;
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		ParticipantCompositor pc = null ;
		
		try {
			pc = ParticipantCompositor.Factory.newInstance( args ) ;
		}
		catch( FactoryException fx ) {
			System.out.println( USAGE + "\n" ) ;
			fx.printStackTrace() ;
			System.exit(1) ;
		}
		
		try {
			pc.exec() ;
		}
		catch( Exception ex ) {
			ex.printStackTrace() ; 
			System.exit(1) ;
		}
		
		System.out.println( "Done!" ) ;	
		System.exit( 0 ) ;

	}
	
	public void exec() throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "exec()" ) ;
		//
		// Form a collection of basic participant data, 
		// with an even number of males and an even number of females...
		formBalancedParticipantCollection() ;
		//
		// Form a map collection of matched pairs;
		// ie: participants matched on gender.
		// We will use this to ensure swapping across questionnaires
		// is consistently of the same matched pairs...
		formMatchedPairs() ;
		//
		// Process each questionnaire which has a swap section ...
		SwapType[] swaps = getCompositionPhase().getSwapArray() ;
		for( SwapType st : swaps ) {
			String questionnaireName = st.getQuestionnaire() ;
			//
			// Locate the questionnaire directory...
			File questionnaireDirectory = locateFile( this.exportDirectory, questionnaireName, true ) ;
			processQuestionnaire( questionnaireDirectory, st ) ;
		}
		if( log.isTraceEnabled() ) exitTrace( "exec()" ) ;
	}
	
	
	private void formBalancedParticipantCollection() throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "formBalancedParticipantCollection()" ) ;
		File particpantsDirectory = locateFile( this.exportDirectory, "Participants", true ) ;
		File entities = locateFile( particpantsDirectory, "entities.xml", false ) ;
		try {
			EntitiesDocument ed = getEntity( entities ) ;
			EntryType[] eta = ed.getEntities().getMap().getEntryArray() ;
			this.participants = new ArrayList<Participant>( eta.length ) ;
			int iMaleCount = 0;
			int iFemaleCount = 0 ;
			for( int i = 0; i < eta.length; i++ ) {
				String[] ida = eta[i].getStringArray() ;
				String id = ida[0] ;
				String fileName = ida[1] ;
				File dataFile = locateFile( particpantsDirectory, fileName, false ) ;
				Participant p = new Participant( id, dataFile ) ;
				if( p.getGender().equalsIgnoreCase( "MALE" ) ) {
					this.participants.add( p ) ;
					iMaleCount++ ;
				}
				else if( p.getGender().equalsIgnoreCase( "FEMALE" ) ) {
					this.participants.add( p ) ;
					iFemaleCount++ ;
				}
				else {
					//
					// If there is no gender recorded, delete all the relevant files from the test domain...
					p.delete() ;
				}
			}
			//
			// Check there are an even number of females and an even number of males
			// for forming composites. A composite must be formed of two males
			// or two females. 
			// For any odds, delete all the relevant files from the test domain. 
			if( iMaleCount%2 != 0) {
				//
				// Delete the first male we come across...
				for(int i=0; i<participants.size(); i++ ) {
					Participant p = participants.get(i) ;
					if( p.getGender().equalsIgnoreCase( "MALE" ) ) {
						participants.remove(i) ;
						p.delete() ;
						break ;
					}
				}
			}
			if( iFemaleCount%2 != 0) {
				//
				// Delete the first female we come across...
				for(int i=0; i<participants.size(); i++ ) {
					Participant p = participants.get(i) ;
					if( participants.get(i).getGender().equalsIgnoreCase( "FEMALE" ) ) {
						participants.remove(i) ;
						p.delete() ;
						break ;
					}
				}
			}
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "formBalancedParticipantCollection()" ) ;
		}	
	}
	
	private void formMatchedPairs() throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "formMatchedPairs()" ) ;
		try {
			this.matchedPairs = new ArrayList<MatchedPair>( this.participants.size() ) ;
			HashMap<String,Participant> usedList = new HashMap<String,Participant>() ;
			@SuppressWarnings("unchecked")
			ArrayList<Participant> duplicateList = (ArrayList<Participant>) this.participants.clone() ;
			Iterator<Participant> it = this.participants.listIterator() ;
			Random r = new Random() ;
			while( it.hasNext() ) {
				Participant one = it.next() ; 
				if( !usedList.containsKey( one.dataFile.getName() ) ) {
					for( int i=0; i<10001; i++ ) {
						if( i == 10000 ) {
							throw new ProcessException( "Loop out of control in formMatchedPairs()" ) ;
						}
						// 
						// Choose a random member of the list...
						Participant two = duplicateList.get( r.nextInt( duplicateList.size() ) ) ;
						//
						// If it is the same member, then cycle around...
						if( two.getDataFile().getName().equals( one.getDataFile().getName() ) ) {
							duplicateList.remove( two ) ;
							continue ;
						}
						//
						// But if two separate particpants of the same gender, 
						// then form a matched pair and add them to the collection.
						// Also remove the found Participant from the duplicate list
						// to ensure it cannot be chosen and matched again...
						if( one.getGender().equals( two.getGender() ) ) {
							MatchedPair mp = new MatchedPair( one.getDataFile(), two.getDataFile() ) ;
							this.matchedPairs.add( mp ) ;
							duplicateList.remove( two ) ;
							usedList.put( two.dataFile.getName(), two ) ;
							break ;
						} 
					}
				}			
			}
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "formMatchedPairs()" ) ;
		}
	}
	
	private File locateFile( File searchDirectory, String targetName, boolean isDirectory ) throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "locateFile()" ) ;
		File[] files = searchDirectory.listFiles();
		try {
			for (File file : files) {
				if (file.getName().equals( targetName )) {
					if( isDirectory && file.isDirectory() ) {
						return file ;
					}
					else if( !isDirectory && file.isFile() ) {
						return file ;
					}
				}
			}
			throw new ProcessException("Could not locate " + ( isDirectory ? "directory":"file" ) + targetName );
		} finally {
			if( log.isTraceEnabled() ) exitTrace( "locateFile()" ) ;
		}		
	}
	
	
	private void processQuestionnaire( File quDirectory, SwapType swapType ) throws ProcessException {
		if (log.isTraceEnabled()) enterTrace("processQuestionnaire()");	
		
		HashMap<String,File> participantFiles = getParticipantFiles( quDirectory ) ;
		Iterator<MatchedPair> it = this.matchedPairs.listIterator() ;
		while( it.hasNext() ) {
			MatchedPair mp = it.next() ;
			File candidateOne = participantFiles.get( mp.fileOneName ) ;
			File candidateTwo = participantFiles.get( mp.fileTwoName ) ;		
			SwappablePair sp = new SwappablePair( candidateOne, candidateTwo, swapType ) ;
			sp.swap() ;
			
		} // end while
		
		if (log.isTraceEnabled()) exitTrace("processQuestionnaire()");
	}	
	
	private LinkedHashMap<String,File> getParticipantFiles( File quDirectory ) {
		if (log.isTraceEnabled()) enterTrace("getParticipantFiles()");
		File[] files = quDirectory.listFiles() ;
		LinkedHashMap<String,File> participantFiles = new LinkedHashMap<String,File>( files.length ) ;
		
		for( File file: files ) {
			//
			// The participants' files start with numerics (eg: 0000002.xml)
			// The conditional traps these...
			if( file.getName().split( "\\." )[0].matches( "\\d+" ) ) {
				log.debug( "File: " + file.getName() ) ;
				participantFiles.put( file.getName(), file ) ;
			}
		}
		if (log.isTraceEnabled()) exitTrace("getParticipantFiles()");
		return participantFiles ;
	}
	
	/**
	 * @param valueSet
	 * @param variableName
	 * @return The element value of the named variable within the given value set.
	 */
	private String getValue( ValueSetType valueSet, String variableName ) {
		if( log.isTraceEnabled() ) enterTrace( "getValue()" ) ;
		try {
			ValueType vt = getValueAsXmlObject( valueSet, variableName ) ;
			if( vt == null ) {
				return null ;
			}
			return getText( vt ) ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getValue()" ) ;
		}
	}
	
	/**
	 * @param valueSet
	 * @param variableName
	 * @return The XML object for the the named value within the given value set.
	 */
	protected ValueType getValueAsXmlObject( ValueSetType valueSet, String variableName ) {
		if( log.isTraceEnabled() ) enterTrace( "getValueAsXmlObject()" ) ;
		try {
			VariableValueType[] vvta = valueSet.getVariableValueArray() ;
			for( int i=0; i<vvta.length; i++ ) {
				if( vvta[i].getVariable().equals( variableName ) ) {
					 return vvta[i].getValue() ;
				}
			}
			return null ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getValueAsXmlObject()" ) ;
		}
	}
	
	
	/**
	 * @param xo
	 * @return the text value of the given XML element 
	 */
	private String getText( XmlObject xo ) {
		XmlCursor cursor = xo.newCursor() ;
		try {
			return cursor.getTextValue().trim() ;
		}
		finally {
			cursor.dispose() ;
		}
	}
	
	private static ValueSetDocument getValueSet( File file ) throws ProcessException {
		if (log.isTraceEnabled()) enterTrace("getValueSet()");
		try {
			return ValueSetDocument.Factory.parse( file ) ;
		}
		catch( IOException iox ) {
			throw new ProcessException( "Something wrong with data file", iox ) ;
		}
		catch( XmlException xmlx ) {
			throw new ProcessException( "Could not parse data file.", xmlx ) ;   			
		}
		finally {
			if (log.isTraceEnabled()) exitTrace("getValueSet()");
		}
	}
	
	private static EntitiesDocument getEntity( File file ) throws ProcessException {
		if (log.isTraceEnabled()) enterTrace("getEntity()");
		try {
			return EntitiesDocument.Factory.parse( file ) ;
		}
		catch( IOException iox ) {
			throw new ProcessException( "Something wrong with entities file", iox ) ;
		}
		catch( XmlException xmlx ) {
			throw new ProcessException( "Could not parse entities file.", xmlx ) ;   			
		}
		finally {
			if (log.isTraceEnabled()) exitTrace("getEntity()");
		}
	}
	

	
	private class SwappablePair {
		
		private File partnerOne ;
		private File partnerTwo ;
		private ValueSetDocument pOneValuesSetDoc ;
		private ValueSetDocument pTwoValuesSetDoc ;
		private SwapType swap ;
		
		private SwappablePair( File candidateOne, File candidateTwo, SwapType swap ) throws ProcessException {
			if (log.isTraceEnabled()) enterTrace("SwappablePair()");
			if( log.isDebugEnabled() ) {
				log.debug( "candidateOne: " + candidateOne.getName() ) ;
				log.debug( "candidateTwo: " + candidateTwo.getName() ) ;
			}
			this.partnerOne = candidateOne ;
			this.partnerTwo = candidateTwo ;
			this.pOneValuesSetDoc = getValueSet( candidateOne ) ;
			this.pTwoValuesSetDoc = getValueSet( candidateTwo ) ;
			this.swap = swap ;
			if (log.isTraceEnabled()) exitTrace("SwappablePair()");
		}
		
		private void swap() throws ProcessException {
			if (log.isTraceEnabled()) enterTrace("SwappablePair.swap()");
			//
			// Process all the selects...
			SelectType[] sta = swap.getSelectArray() ;
			for( int i=0; i<sta.length; i++ ) {
				processSelect( sta[i] ) ;
			}
			//
			// Update the files...
			saveValueSetDoc(pOneValuesSetDoc, partnerOne ) ;
			saveValueSetDoc(pTwoValuesSetDoc, partnerTwo ) ;
			
			if (log.isTraceEnabled()) exitTrace("SwappablePair.swap()");
		}
		
		public void saveValueSetDoc( ValueSetDocument vsd, File file ) throws ProcessException {
			if (log.isTraceEnabled()) enterTrace("SwappablePair.saveValueSetDoc()");
			try {		
				XmlOptions opts = getSaveOptions() ;
				vsd.save( file, opts ) ;
			}
			catch( Exception iox ) {
				String message = "Save value sets file failed: " + file.getAbsolutePath() ;
				throw new ProcessException( message, iox ) ;			
			}
			finally { 
				if( log.isTraceEnabled() ) exitTrace( "SwappablePair.saveValueSetDoc()" ) ;
			}
		}
		
	    private XmlOptions getSaveOptions() {
	        XmlOptions opts = new XmlOptions();
	        opts.setSaveOuter() ;
	        opts.setSaveNamespacesFirst() ;
	        opts.setSaveAggressiveNamespaces() ;  
	             
	        opts.setSavePrettyPrint() ;
	        opts.setSavePrettyPrintIndent( 3 ) ; 
	        return opts ;
	    }
		
		private void processSelect( SelectType select ) throws ProcessException {
			if (log.isTraceEnabled()) enterTrace("SwappablePair.processSelect()");
			
			ArrayList<VariableValueType> p1vars = getSwappableVars( pOneValuesSetDoc, select ) ;
			ArrayList<VariableValueType> p2vars = getSwappableVars( pTwoValuesSetDoc, select ) ;			
			//
			// First, delete the variables from the respective participants,
			// but return clones of the variables...
			p1vars = deleteVars( p1vars ) ;
			p2vars = deleteVars( p2vars ) ;
			//
			// Second, update the opposite participant with inserts of the partner variables...
			updateVars( pOneValuesSetDoc.getValueSet(), p2vars ) ;
			updateVars( pTwoValuesSetDoc.getValueSet(), p1vars ) ;
			
			if (log.isTraceEnabled()) exitTrace("SwappablePair.processSelect()");
		}
		
		private ArrayList<VariableValueType> deleteVars( ArrayList<VariableValueType> vars ) {
			if (log.isTraceEnabled()) enterTrace("SwappablePair.deleteVars()");
			ArrayList<VariableValueType> clones = new ArrayList<VariableValueType>( vars.size() ) ; 
			Iterator<VariableValueType> it = vars.listIterator() ;
			while( it.hasNext() ) {
				VariableValueType vvt = it.next() ;
				clones.add( (VariableValueType)vvt.copy() ) ;
				XmlCursor cursor = vvt.newCursor() ;
				try {
					cursor.removeXml() ;
				}
				finally {
					cursor.dispose() ;
				}				
			}
			if (log.isTraceEnabled()) exitTrace("SwappablePair.deleteVars()");
			return clones ;
		}
		
		private void updateVars( ValueSetType valueSetType, ArrayList<VariableValueType> clones ) {
			if (log.isTraceEnabled()) enterTrace("SwappablePair.updateVars()");
			
			Iterator<VariableValueType> it = clones.listIterator() ;
			while( it.hasNext() ) {
				VariableValueType vvt = it.next() ;
				VariableValueType nvvt = valueSetType.addNewVariableValue() ;
				nvvt.set( vvt ) ;
			}
			
			if (log.isTraceEnabled()) exitTrace("SwappablePair.updateVars()");
		}
		
		
		
		private ArrayList<VariableValueType> getSwappableVars( ValueSetDocument valueSetDoc
				                                             , SelectType select ) throws ProcessException {
			if (log.isTraceEnabled()) enterTrace("SwappablePair.getSwappableVars()");
			ArrayList<VariableValueType> vars = new ArrayList<VariableValueType>();
			VariableValueType[] vvta = valueSetDoc.getValueSet().getVariableValueArray() ;
			for( VariableValueType vvt : vvta) {
				String varName = vvt.getVariable() ;
				if( isSwappable( varName, select ) ) {
					vars.add( vvt ) ;
				}
			}
			if (log.isTraceEnabled()) exitTrace("SwappablePair.getSwappableVars()");
			return vars ;
		}
		
		private boolean isSwappable( String varName, SelectType select ) {
			if (log.isTraceEnabled()) enterTrace("SwappablePair.isSwappable()");
			if( log.isDebugEnabled() ) {
				log.debug( "varName: " + varName ) ;
			}
			String[] hints = select.getHintArray() ;
			String[] excludes = select.getExcludeArray() ;
			String[] explicitIncludes = select.getExplicitArray() ;
			try {
				//
				// Check for explicit inclusion first
				// If allowed, this will override hints and excludes...
				for( int i=0; i<explicitIncludes.length; i++ ) {
					if( varName.equals( explicitIncludes[i] ) ) {
						if( log.isDebugEnabled() ) {
							log.debug( "returned true" ) ;
						}
						return true ;
					}
				}
				//
				// Hints and excludes work in tandem...
				outerLoop: for( int i=0; i<hints.length; i++ ) {
					if( varName.contains( hints[i] ) ) {
						for( int j=0; j<excludes.length; j++ ) {
							if( varName.contains( excludes[j] ) ) {
								continue outerLoop ;
							}
						}
						if( log.isDebugEnabled() ) {
							log.debug( "returned true" ) ;
						}
						return true ;
					}
				}
				if( log.isDebugEnabled() ) {
					log.debug( "returned false" ) ;
				}
				return false ;				
			}
			finally {
				if (log.isTraceEnabled()) exitTrace("SwappablePair.isSwappable()");
			}		
			
		}
		
	} // end of class SwappablePair
	
	/**
	 * @author jeff
	 *
	 */
	public class Participant {
		
		public String id ;
		public String gender ;
		public File dataFile ;
		public ValueSetType valueSet ;
		
		public Participant( String id, File dataFile ) throws ProcessException {
			this.id = id ;
			this.dataFile = dataFile ;
			this.valueSet = getValueSet( dataFile ).getValueSet() ;
		}
		
		public String getGender() {
			if( gender == null ) {
				gender = getValue( valueSet, "Admin.Participant.gender" ) ;
			}
			return gender ;
		}
		
		public File getDataFile() {
			return dataFile;
		}
		
		/**
		 * We need to remove all files and references to this participant.
		 */
		public void delete() throws ProcessException {
			if (log.isTraceEnabled()) enterTrace("Participant.delete()");
			try {
				File[] directories = exportDirectory.listFiles() ;
				//
				// For all questionnaire directories within the export directory...
				for (File directory : directories) {
					if( !directory.isDirectory() ) {
						continue ;
					}
					if( log.isDebugEnabled() ) {
						log.debug( "file: " + directory.getName() ) ;
					}
					File[] files = directory.listFiles();
					//
					// For all "ordinary" files within a questionnaire directory...
					for( int j=0; j<files.length; j++ ) {
//						if( log.isDebugEnabled() ) {
//							log.debug( "file: " + j ) ;
//						}
						//
						// The entities.xml file.
						// We remove the relevant entry and update the file...
						if( files[j].getName().equals( "entities.xml" ) ) {
							EntitiesDocument ed = getEntity( files[j] ) ;
							EntryType[] eta = ed.getEntities().getMap().getEntryArray() ;
							for( int i=0; i<eta.length; i++ ) {
								if( eta[i].getStringArray(0).equals(id) ) {
									if( eta[i].getStringArray(1).equals( dataFile.getName() ) ) {
										ed.getEntities().getMap().removeEntry(i) ;
										// need to write out the file here!
										saveEntitiesDoc( ed, files[j] ) ;
									}
									else {
										// throw an exception here!
										log.error( "Integrity issue with entities.xml file" ) ;
									}
								}
							}
						}
						//
						// A data file (eg: 0000001.xml).
						// When we find the correct one, we delete it...
						else if( files[j].getName().equals( dataFile.getName() ) ) {
							files[j].delete() ;
						}
					}
				}
				dataFile = null ;
			}
			finally {
				if (log.isTraceEnabled()) exitTrace("Participant.delete()");
			}			
		}
		
		public void saveEntitiesDoc( EntitiesDocument ed, File file ) throws ProcessException {
			if (log.isTraceEnabled()) exitTrace("Participant.saveEntitiesDoc()");
			try {		
				XmlOptions opts = getEntitySaveOptions() ;
				ed.save( file, opts ) ;
			}
			catch( Exception iox ) {
				String message = "Save entities file failed: " + file.getAbsolutePath() ;
				throw new ProcessException( message, iox ) ;			
			}
			finally { 
				if( log.isTraceEnabled() ) exitTrace( "Participant.saveEntitiesDoc()" ) ;
			}
		}
		
	    /**
	     * Returns the <code>XmlOptions</code> required to produce
	     * a text representation of the emitted XML.
	     * 
	     * @return XmlOptions
	     */
	    private XmlOptions getEntitySaveOptions() {
	        XmlOptions opts = new XmlOptions();
	        opts.setSaveOuter() ;
	        opts.setSaveNamespacesFirst() ;
	        opts.setSaveAggressiveNamespaces() ;  
	        
	        HashMap<String, String> suggestedPrefixes = new HashMap<String, String>();
	        suggestedPrefixes.put("http://brisskit.org/xml/onyx-entities/v1.0/oe", "oe");
	        opts.setSaveSuggestedPrefixes(suggestedPrefixes);
	              
	        opts.setSavePrettyPrint() ;
	        opts.setSavePrettyPrintIndent( 3 ) ; 
	        return opts ;
	    }
		
	} // end of class Participant

	public class MatchedPair {
		
		protected String fileOneName ;
		protected String fileTwoName ;
		
		public MatchedPair( File fileOne, File fileTwo ) {
			this.fileOneName = fileOne.getName() ;
			this.fileTwoName = fileTwo.getName() ;
		}
		
		public String getPartnerFileName( File key ) throws ProcessException {
			if( fileOneName.equals( key.getName() ) ) {
				return fileTwoName ;
			}
			else if( fileTwoName.equals( key.getName() ) ) {
				return fileOneName ;
			}
			throw new ProcessException( "Integrity issues with MatchedPair" ) ;
		}
		
	}
	 /**
     * Utility class covering all exceptions .
     * 
     * @author jl99
     *
     */
    public static class ProcessException extends Exception {
    	
    	/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		public ProcessException( String message ) {
    		super( message ) ;
    	}
    	
    	public ProcessException( String message, Throwable cause ) {
    		super( message, cause ) ;
    	}
    	
    }
	
	public static class Factory {
				
		public static ParticipantCompositor newInstance( String[] args ) throws FactoryException {
			
			File exportDirectory = null ;
			OnyxExportConfigDocument config = null ;

			if( args != null && args.length > 0 ) {

				for( int i=0; i<args.length; i++ ) {

					if( args[i].startsWith( "-export=" ) && exportDirectory == null ) { 
						exportDirectory = newExportDirectory( args[i].substring(8) ) ;
					}
					else if( args[i].startsWith( "-e=" ) && exportDirectory == null ) { 
						exportDirectory = newExportDirectory( args[i].substring(3) ) ;
					}
					else if( args[i].startsWith( "-config=" ) && config == null ) { 
						config = newConfiguration( args[i].substring(8) ) ;
					}
					else if( args[i].startsWith( "-c=" ) && config == null ) { 
						config = newConfiguration( args[i].substring(3) ) ;
					}
				}
			}
			
			if( exportDirectory == null ) {
				throw new FactoryException( "Export directory path missing." ) ;
			}
			else if( config == null ) {
				throw new FactoryException( "Configuration path missing." ) ;
			}

			ParticipantCompositor pc = new ParticipantCompositor() ;
			pc.setExportDirectory( exportDirectory ) ;
			pc.setConfig( config ) ;
			return pc ;
		}
		
		private static File newExportDirectory( String path ) throws FactoryException {
			File file = new File( path ) ;
			if( !file.exists() ) {
				throw new FactoryException( "Export directory does not exist." ) ;
			}
			if( !file.isDirectory() ) {
				throw new FactoryException( "Export parameter does not refer to a directory" ) ;
			}
			return file ;
		}
		
		private static OnyxExportConfigDocument newConfiguration( String path ) throws FactoryException {
			try {
				OnyxExportConfigDocument config = OnyxExportConfigDocument.Factory.parse( new File( path ) ) ;
				CompositionPhaseType cpt = config.getOnyxExportConfig().getCompositionPhase() ;
				if( cpt == null ) {
					throw new FactoryException( "Configuration file does not contain a composition-phase." ) ;
				}
				return config ;
			}
			catch( Exception ex ) {
				throw new FactoryException( "Could not create configuration.", ex ) ;
			}			
		}
	
	}
	
	 /**
     * Utility class covering all exceptions thrown by the Factory object.
     * 
     * @author jl99
     *
     */
    public static class FactoryException extends Exception {
    	
    	/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		public FactoryException( String message ) {
    		super( message ) ;
    	}
    	
    	public FactoryException( String message, Throwable cause ) {
    		super( message, cause ) ;
    	}
    	
    }
    
    /**
	 * Utility routine to enter a structured message in the trace log that the given method 
	 * has been entered. 
	 * 
	 * @param entry: the name of the method entered
	 */
	public static void enterTrace( String entry ) {
		log.trace( getIndent().toString() + "enter: " + entry ) ;
		indentPlus() ;
	}

    /**
     * Utility routine to enter a structured message in the trace log that the given method 
	 * has been exited.
	 * 
     * @param entry: the name of the method exited
     */
    public static void exitTrace( String entry ) {
    	indentMinus() ;
		log.trace( getIndent().toString() + "exit : " + entry ) ;
	}
	
    /**
     * Utility method used to maintain the structured trace log.
     */
    public static void indentPlus() {
		getIndent().append( ' ' ) ;
	}
	
    /**
     * Utility method used to maintain the structured trace log.
     */
    public static void indentMinus() {
        if( logIndent.length() > 0 ) {
            getIndent().deleteCharAt( logIndent.length()-1 ) ;
        }
	}
	
    /**
     * Utility method used for indenting the structured trace log.
     */
    public static StringBuffer getIndent() {
	    if( logIndent == null ) {
	       logIndent = new StringBuffer() ;	
	    }
	    return logIndent ;	
	}
    
    @SuppressWarnings("unused")
	private static void resetIndent() {
        if( logIndent != null ) { 
            if( logIndent.length() > 0 ) {
               logIndent.delete( 0, logIndent.length() )  ;
            }
        }   
    }

}
