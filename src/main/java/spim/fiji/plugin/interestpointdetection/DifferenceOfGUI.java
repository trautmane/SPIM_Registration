package spim.fiji.plugin.interestpointdetection;

import ij.gui.GenericDialog;

import java.util.ArrayList;
import java.util.List;

import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import spim.fiji.plugin.util.GUIHelper;
import spim.fiji.spimdata.SpimData2;

public abstract class DifferenceOfGUI extends InterestPointDetectionGUI
{
	protected static final int[] ds = { 1, 2, 4, 8 };

	public static String[] downsampleChoiceXY = { ds[ 0 ] + "x", ds[ 1 ] + "x", ds[ 2 ] + "x", ds[ 3 ] + "x", "Match Z Resolution (less downsampling)", "Match Z Resolution (more downsampling)"  };
	public static String[] downsampleChoiceZ = { ds[ 0 ] + "x", ds[ 1 ] + "x", ds[ 2 ] + "x", ds[ 3 ] + "x" };
	public static String[] localizationChoice = { "None", "3-dimensional quadratic fit", "Gaussian mask localization fit" };	
	public static String[] brightnessChoice = { "Very weak & small (beads)", "Weak & small (beads)", "Comparable to Sample & small (beads)", "Strong & small (beads)", "Advanced ...", "Interactive ..." };
	
	public static int defaultDownsampleXYIndex = 4;
	public static int defaultDownsampleZIndex = 0;

	public static int defaultLocalization = 1;
	public static int defaultBrightness = 5;

	public static double defaultImageSigmaX = 0.5;
	public static double defaultImageSigmaY = 0.5;
	public static double defaultImageSigmaZ = 0.5;

	public static int defaultViewChoice = 0;

	public static double defaultAdditionalSigmaX = 0.0;
	public static double defaultAdditionalSigmaY = 0.0;
	public static double defaultAdditionalSigmaZ = 0.0;

	public static double defaultMinIntensity = 0.0;
	public static double defaultMaxIntensity = 65535.0;
	
	protected double imageSigmaX, imageSigmaY, imageSigmaZ;
	protected double minIntensity, maxIntensity;

	// downsampleXY == 0 : a bit less then z-resolution
	// downsampleXY == -1 : a bit more then z-resolution
	protected int localization, downsampleXY, downsampleZ;

	public DifferenceOfGUI( final SpimData2 spimData, final List< ViewId > viewIdsToProcess )
	{
		super( spimData, viewIdsToProcess );
	}

	protected abstract void addAddtionalParameters( final GenericDialog gd );
	protected abstract boolean queryAdditionalParameters( final GenericDialog gd );

	@Override
	public boolean queryParameters( final boolean defineAnisotropy, final boolean setMinMax )
	{
		final GenericDialog gd = new GenericDialog( getDescription() );
		gd.addChoice( "Subpixel_localization", localizationChoice, localizationChoice[ defaultLocalization ] );
		gd.addChoice( "Interest_point_specification", brightnessChoice, brightnessChoice[ defaultBrightness ] );

		gd.addChoice( "Downsample_XY", downsampleChoiceXY, downsampleChoiceXY[ defaultDownsampleXYIndex ] );
		gd.addChoice( "Downsample_Z", downsampleChoiceZ, downsampleChoiceZ[ defaultDownsampleZIndex ] );

		if ( setMinMax )
		{
			gd.addNumericField( "Minimal_intensity", defaultMinIntensity, 1 );
			gd.addNumericField( "Maximal_intensity", defaultMaxIntensity, 1 );
		}

		if ( defineAnisotropy )
		{
			gd.addNumericField( "Image_Sigma_X", defaultImageSigmaX, 5 );
			gd.addNumericField( "Image_Sigma_Y", defaultImageSigmaY, 5 );
			gd.addNumericField( "Image_Sigma_Z", defaultImageSigmaZ, 5 );
			
			gd.addMessage( "Please consider that usually the lower resolution in z is compensated by a lower sampling rate in z.\n" +
					"Only adjust the initial sigma's if this is not the case.", GUIHelper.mediumstatusfont );
		}

		addAddtionalParameters( gd );

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return false;
		
		this.localization = defaultLocalization = gd.getNextChoiceIndex();

		final int brightness = gd.getNextChoiceIndex();

			int dsxy = defaultDownsampleXYIndex = gd.getNextChoiceIndex();
			int dsz = defaultDownsampleZIndex = gd.getNextChoiceIndex();

			if ( dsz == 0 )
				downsampleZ = 1;
			else if ( dsz == 1 )
				downsampleZ = 2;
			else if ( dsz == 2 )
				downsampleZ = 4;
			else
				downsampleZ = 8;

			if ( dsxy == 0 )
				downsampleXY = 1;
			else if ( dsxy == 1 )
				downsampleXY = 2;
			else if ( dsxy == 2 )
				downsampleXY = 4;
			else if ( dsxy == 3 )
				downsampleXY = 8;
			else if ( dsxy == 4 )
				downsampleXY = 0;
			else
				downsampleXY = -1;

		if ( setMinMax )
		{
			minIntensity = defaultMinIntensity = gd.getNextNumber();
			maxIntensity = defaultMaxIntensity = gd.getNextNumber();
		}
		else
		{
			minIntensity = maxIntensity = Double.NaN;
		}

		if ( brightness <= 3 )
		{
			if ( !setDefaultValues( brightness ) )
				return false;
		}
		else if ( brightness == 4 )
		{
			if ( !setAdvancedValues() )
				return false;
		}
		else
		{
			if ( !setInteractiveValues() )
				return false;
		}

		if ( defineAnisotropy )
		{
			imageSigmaX = defaultImageSigmaX = gd.getNextNumber();
			imageSigmaY = defaultImageSigmaY = gd.getNextNumber();
			imageSigmaZ = defaultImageSigmaZ = gd.getNextNumber();
		}
		else
		{
			imageSigmaX = imageSigmaY = imageSigmaZ = 0.5;
		}

		if ( !queryAdditionalParameters( gd ) )
			return false;
		else
			return true;
	}

	/**
	 * Figure out which view to use for the interactive preview
	 * 
	 * @param dialogHeader
	 * @param text
	 * @param channel
	 * @return
	 */
	protected ViewId getViewSelection( final String dialogHeader, final String text, final Channel channel )
	{
		final ArrayList< ViewDescription > views = SpimData2.getAllViewIdsForChannelSorted( spimData, viewIdsToProcess, channel );
		final String[] viewChoice = new String[ views.size() ];

		for ( int i = 0; i < views.size(); ++i )
		{
			final ViewDescription vd = views.get( i );
			viewChoice[ i ] = "Timepoint " + vd.getTimePointId() + ", Angle " + vd.getViewSetup().getAngle().getName() + ", Illum " + vd.getViewSetup().getIllumination().getName() + ", ViewSetupId " + vd.getViewSetupId();
		}

		if ( defaultViewChoice >= views.size() )
			defaultViewChoice = 0;

		final GenericDialog gd = new GenericDialog( dialogHeader );

		gd.addMessage( text );
		gd.addChoice( "View", viewChoice, viewChoice[ defaultViewChoice ] );
		
		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return null;

		final ViewId viewId = views.get( defaultViewChoice = gd.getNextChoiceIndex() );

		return viewId;
	}

	protected abstract boolean setDefaultValues( final int brightness );
	protected abstract boolean setAdvancedValues();
	protected abstract boolean setInteractiveValues();
}