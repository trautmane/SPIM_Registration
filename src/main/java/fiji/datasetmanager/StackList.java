package fiji.datasetmanager;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.gui.GenericDialog;

import java.awt.BorderLayout;
import java.awt.Checkbox;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Panel;
import java.awt.ScrollPane;
import java.awt.Toolkit;
import java.io.File;
import java.util.ArrayList;

import mpicbg.spim.io.ConfigurationParserException;

public abstract class StackList implements MultiViewDatasetDefinition
{
	final public static char TIMEPOINT_PATTERN = 't';
	final public static char CHANNEL_PATTERN = 'c';
	final public static char ILLUMINATION_PATTERN = 'i';
	final public static char ANGLE_PATTERN = 'a';
	
	public static boolean defaultHasMultipleAngles = true;
	public static boolean defaultHasMultipleTimePoints = true;
	public static boolean defaultHasMultipleChannels = false;
	public static boolean defaultHasMultipleIlluminations = false;
	
	protected boolean hasMultipleAngles, hasMultipleTimePoints, hasMultipleChannels, hasMultipleIlluminations;
	
	public static boolean showDebugFileNames = true;
	
	public static String defaultTimepoints = "20-50";
	public static String defaultChannels = "1,2";
	public static String defaultIlluminations = "0,1";
	public static String defaultAngles = "0-315:45";

	protected String timepoints, channels, illuminations, angles;
	protected ArrayList< Integer > timepointList, channelList, illuminationsList, angleList;
	protected String replaceTimepoints, replaceChannels, replaceIlluminations, replaceAngles;
	protected int numDigitsTimepoints, numDigitsChannels, numDigitsIlluminations, numDigitsAngles;
	
	protected ArrayList< int[] > exceptionIds;
	
	protected String[] calibrationChoice = new String[]{ "Same calibration for all files (load from first file)", "Same calibration for all files (user defined)", "Load calibration for each file individually" };
	public static int defaultCalibration = 0;
	public int calibation;
	
	public static String defaultDirectory = "";
	public static String defaultFileNamePattern = null;

	protected String directory, fileNamePattern;
	
	protected double calX = 1, calY = 1, calZ = 1;
	protected String calUnit = "µm";

	protected boolean queryInformation()
	{		
		try 
		{
			if ( !queryGeneralInformation() )
				return false;
			
			if ( defaultFileNamePattern == null )
				defaultFileNamePattern = assembleDefaultPattern();

			if ( !queryNames() )
				return false;

			if ( showDebugFileNames && !debugShowFiles() )
				return false;
			
			if ( calibation == 0 && !loadFirstCalibration() )
				return false;
			
			if ( !queryDetails() )
				return false;
		} 
		catch ( ConfigurationParserException e )
		{
			IJ.log( e.toString() );
			return false;
		}
				
		return true;
	}
	
	/**
	 * Assemble the filename for the corresponding file based on the indices for time, channel, illumination and angle
	 * 
	 * @param tpID
	 * @param chID
	 * @param illID
	 * @param angleID
	 * @return
	 */
	protected String getFileNameFor( final int tpID, final int chID, final int illID, final int angleID )
	{
		String fileName = fileNamePattern;
		
		if ( hasMultipleTimePoints )
			fileName = fileName.replace( replaceTimepoints, "" + timepointList.get( tpID ) );

		if ( hasMultipleChannels )
			fileName = fileName.replace( replaceChannels, "" + channelList.get( chID ) );

		if ( hasMultipleIlluminations )
			fileName = fileName.replace( replaceIlluminations, "" + illuminationsList.get( illID ) );

		if ( hasMultipleAngles )
			fileName = fileName.replace( replaceAngles, "" + angleList.get( angleID ) );
		
		return fileName;
	}
	
	/**
	 * populates the fields calX, calY, calZ from the first file of the series
	 * 
	 * @return - true if successful
	 */
	protected boolean loadFirstCalibration()
	{
		for ( int t = 0; t < timepointList.size(); ++t )
			for ( int c = 0; c < channelList.size(); ++c )
				for ( int i = 0; i < illuminationsList.size(); ++i )
					for ( int a = 0; a < angleList.size(); ++a )
					{
						if ( exceptionIds.size() > 0 && 
							 exceptionIds.get( 0 )[ 0 ] == t && exceptionIds.get( 0 )[ 1 ] == c && 
							 exceptionIds.get( 0 )[ 2 ] == t && exceptionIds.get( 0 )[ 3 ] == a )
						{
							continue;
						}
						else
						{
							return loadCalibration( new File( directory, getFileNameFor( t, c, i, a ) ) );
						}
					}
		
		return false;
	}
	
	/**
	 * Loads the calibration stored in a specific file and closes it afterwards. Depends on the type of opener that is used.
	 * 
	 * @param file
	 * @return
	 */
	protected abstract boolean loadCalibration( final File file );
	
	protected boolean queryDetails()
	{
		final GenericDialog gd = new GenericDialog( "Define dataset (3/3)" );
		
		gd.addMessage( "Channel definitions", new Font( Font.SANS_SERIF, Font.BOLD, 14 ) );
		gd.addMessage( "" );
		
		if ( hasMultipleChannels )
		{
			for ( int c = 0; c < channelList.size(); ++c )
				gd.addCheckbox( "Beads_visible_in_channel_" + channelList.get( c ), true );
		}
		else
		{
			gd.addCheckbox( "Beads_visible_in_default_channel", true );
		}

		if ( calibation < 2 )
		{
			gd.addMessage( "" );
			gd.addMessage( "Calibration", new Font( Font.SANS_SERIF, Font.BOLD, 14 ) );			
			if ( calibation == 1 )
				gd.addMessage( "(read from file)", new Font( Font.SANS_SERIF, Font.ITALIC, 11 ) );
			gd.addMessage( "" );
			
			gd.addNumericField( "Pixel_distance_x", calX, 5 );
			gd.addNumericField( "Pixel_distance_y", calY, 5 );
			gd.addNumericField( "Pixel_distance_z", calZ, 5 );
			gd.addStringField( "Pixel_unit", calUnit );
		}

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return false;
		
		return true;
	}
	
	protected boolean debugShowFiles()
	{
		final GenericDialog gd = new GenericDialog( "3d image stacks files" );

		gd.addMessage( "" );
		gd.addMessage( "Path: " + directory + "   " );

		for ( int t = 0; t < timepointList.size(); ++t )
			for ( int c = 0; c < channelList.size(); ++c )
				for ( int i = 0; i < illuminationsList.size(); ++i )
					for ( int a = 0; a < angleList.size(); ++a )
					{
						String fileName = getFileNameFor( t, c, i, a );

						gd.addCheckbox( fileName, true );
						
						// otherwise underscores are gone ...
						((Checkbox)gd.getCheckboxes().lastElement()).setLabel( fileName );
					}
				
		addScrollBars( gd );
		
		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return false;

		exceptionIds = new ArrayList<int[]>();

		// collect exceptions to the definitions
		for ( int t = 0; t < timepointList.size(); ++t )
			for ( int c = 0; c < channelList.size(); ++c )
				for ( int i = 0; i < illuminationsList.size(); ++i )
					for ( int a = 0; a < angleList.size(); ++a )
						if ( gd.getNextBoolean() == false )
							exceptionIds.add( new int[]{ t, c, i, a } );					
				
		return true;
	}
	
	protected boolean queryNames() throws ConfigurationParserException
	{
		final GenericDialogPlus gd = new GenericDialogPlus( "Define dataset (2/3)" );
		
		gd.addDirectoryOrFileField( "Image_File_directory", defaultDirectory );
		gd.addStringField( "Image_File_Pattern", defaultFileNamePattern, 40 );

		if ( hasMultipleTimePoints )
			gd.addStringField( "Timepoints", defaultTimepoints );
		
		if ( hasMultipleChannels )
			gd.addStringField( "Channels", defaultChannels );

		if ( hasMultipleIlluminations )
			gd.addStringField( "Illumination_directions", defaultIlluminations );
		
		if ( hasMultipleAngles )
			gd.addStringField( "Acquisition_angles", defaultAngles );
		
		gd.addChoice( "Calibration", calibrationChoice, calibrationChoice[ defaultCalibration ] );
		
		gd.addCheckbox( "Show_list of filenames (to debug and it allows to deselect individual files)", showDebugFileNames );
		gd.addMessage( "Note: this might take a few seconds if thousands of files are present", new Font( Font.SANS_SERIF, Font.ITALIC, 11 ) );
		
		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return false;
		
		defaultDirectory = directory = gd.getNextString();
		defaultFileNamePattern = fileNamePattern = gd.getNextString();

		timepoints = channels = illuminations = angles = null;
		replaceTimepoints = replaceChannels = replaceIlluminations = replaceAngles = null;
		
		// get the String patterns and verify that the corresponding pattern, 
		// e.g. {t} or {tt} exists in the pattern
		if ( hasMultipleTimePoints )
		{
			defaultTimepoints = timepoints = gd.getNextString();
			replaceTimepoints = IntegerPattern.getReplaceString( fileNamePattern, TIMEPOINT_PATTERN );
			
			if ( replaceTimepoints == null )
				throw new ConfigurationParserException( "Pattern {" + TIMEPOINT_PATTERN + "} not present in " + fileNamePattern + 
						" although you indicated there would be several timepoints." );
			
			numDigitsTimepoints = replaceTimepoints.length() - 2;
		}

		if ( hasMultipleChannels )
		{
			defaultChannels = channels = gd.getNextString();
			replaceChannels = IntegerPattern.getReplaceString( fileNamePattern, CHANNEL_PATTERN );
			
			if ( replaceChannels == null )
				throw new ConfigurationParserException( "Pattern {" + CHANNEL_PATTERN + "} not present in " + fileNamePattern + 
						" although you indicated there would be several channels." );
			
			numDigitsChannels = replaceChannels.length() - 2;
		}

		if ( hasMultipleIlluminations )
		{
			defaultIlluminations = illuminations = gd.getNextString();
			replaceIlluminations = IntegerPattern.getReplaceString( fileNamePattern, ILLUMINATION_PATTERN );
			
			if ( replaceIlluminations == null )
				throw new ConfigurationParserException( "Pattern {" + ILLUMINATION_PATTERN + "} not present in " + fileNamePattern + 
						" although you indicated there would be several illumination directions." );

			numDigitsIlluminations = replaceIlluminations.length() - 2;
		}

		if ( hasMultipleAngles )
		{
			defaultAngles = angles = gd.getNextString();
			replaceAngles = IntegerPattern.getReplaceString( fileNamePattern, ANGLE_PATTERN );
			
			if ( replaceAngles == null )
				throw new ConfigurationParserException( "Pattern {" + ANGLE_PATTERN + "} not present in " + fileNamePattern + 
						" although you indicated there would be several angles." );
			
			numDigitsAngles = replaceAngles.length() - 2;
		}

		// get the list of integers
		timepointList = IntegerPattern.parseIntegerString( timepoints, "timepoints" );
		channelList = IntegerPattern.parseIntegerString( channels, "channels" );
		illuminationsList = IntegerPattern.parseIntegerString( illuminations, "illumination directions" );
		angleList = IntegerPattern.parseIntegerString( angles, "acquisiton angles" );

		exceptionIds = new ArrayList< int[] >();
		
		defaultCalibration = calibation = gd.getNextChoiceIndex();
		showDebugFileNames = gd.getNextBoolean();
		
		return true;		
	}
	
	protected String assembleDefaultPattern()
	{
		String pattern = "spim";
		
		if ( hasMultipleTimePoints )
			pattern += "_TL{t}";
		
		if ( hasMultipleChannels )
			pattern += "_Channel{c}";
		
		if ( hasMultipleIlluminations )
			pattern += "_Illum{i}";
		
		if ( hasMultipleAngles )
			pattern += "_Angle{a}";
		
		return pattern + ".tif";
	}
	
	protected boolean queryGeneralInformation()
	{
		final GenericDialogPlus gd = new GenericDialogPlus( "Define dataset (1/3)" );
		
		gd.addCheckbox( "Dataset_with_multiple_timepoints", defaultHasMultipleTimePoints );
		gd.addCheckbox( "Dataset_with_multiple_channels", defaultHasMultipleChannels );
		gd.addCheckbox( "Dataset_with_multiple_illumination_directions", defaultHasMultipleIlluminations );
		gd.addCheckbox( "Dataset_with_multiple_angles", defaultHasMultipleAngles );
		
		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return false;
		
		hasMultipleTimePoints = defaultHasMultipleTimePoints = gd.getNextBoolean();
		hasMultipleChannels = defaultHasMultipleChannels = gd.getNextBoolean();
		hasMultipleIlluminations = defaultHasMultipleIlluminations = gd.getNextBoolean();
		hasMultipleAngles = defaultHasMultipleAngles = gd.getNextBoolean();
		
		return true;
	}
	
	/**
	 * A copy of Curtis's method
	 * 
	 * https://github.com/openmicroscopy/bioformats/blob/v4.4.8/components/loci-plugins/src/loci/plugins/util/WindowTools.java#L72
	 * 
	 * <dependency>
     * <groupId>${bio-formats.groupId}</groupId>
     * <artifactId>loci_plugins</artifactId>
     * <version>${bio-formats.version}</version>
     * </dependency>
	 * 
	 * @param pane
	 */
	public static void addScrollBars(Container pane) {
		GridBagLayout layout = (GridBagLayout) pane.getLayout();

		// extract components
		int count = pane.getComponentCount();
		Component[] c = new Component[count];
		GridBagConstraints[] gbc = new GridBagConstraints[count];
		for (int i = 0; i < count; i++) {
			c[i] = pane.getComponent(i);
			gbc[i] = layout.getConstraints(c[i]);
		}

		// clear components
		pane.removeAll();
		layout.invalidateLayout(pane);

		// create new container panel
		Panel newPane = new Panel();
		GridBagLayout newLayout = new GridBagLayout();
		newPane.setLayout(newLayout);
		for (int i = 0; i < count; i++) {
			newLayout.setConstraints(c[i], gbc[i]);
			newPane.add(c[i]);
		}

		// HACK - get preferred size for container panel
		// NB: don't know a better way:
		// - newPane.getPreferredSize() doesn't work
		// - newLayout.preferredLayoutSize(newPane) doesn't work
		Frame f = new Frame();
		f.setLayout(new BorderLayout());
		f.add(newPane, BorderLayout.CENTER);
		f.pack();
		final Dimension size = newPane.getSize();
		f.remove(newPane);
		f.dispose();

		// compute best size for scrollable viewport
		size.width += 25;
		size.height += 15;
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		int maxWidth = 7 * screen.width / 8;
		int maxHeight = 3 * screen.height / 4;
		if (size.width > maxWidth)
			size.width = maxWidth;
		if (size.height > maxHeight)
			size.height = maxHeight;

		// create scroll pane
		ScrollPane scroll = new ScrollPane() {
			private static final long serialVersionUID = 1L;

			public Dimension getPreferredSize() {
				return size;
			}
		};
		scroll.add(newPane);

		// add scroll pane to original container
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridwidth = GridBagConstraints.REMAINDER;
		constraints.fill = GridBagConstraints.BOTH;
		constraints.weightx = 1.0;
		constraints.weighty = 1.0;
		layout.setConstraints(scroll, constraints);
		pane.add(scroll);
	}	
}