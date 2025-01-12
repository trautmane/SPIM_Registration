/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2007 - 2021 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package mpicbg.spim.fusion;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;

import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.display.imagej.ImageJFunctions;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.spim.io.IOFunctions;
import mpicbg.spim.io.SPIMConfiguration;
import mpicbg.spim.registration.ViewDataBeads;
import mpicbg.spim.registration.ViewStructure;

public class FusionControl
{	
	SPIMImageFusion fusion;
	
	public SPIMImageFusion getFusion() { return fusion; }
	
	public void fuse( final ViewStructure viewStructure, final int timePoint )
	{
		fuse( viewStructure, viewStructure, timePoint );
	}
	
	public void fuse( final ViewStructure viewStructure, final ViewStructure referenceViewStructure, final int timePoint)
	{
		final SPIMConfiguration conf = viewStructure.getSPIMConfiguration();
		
		final ArrayList<IsolatedPixelWeightenerFactory<?>> isolatedWeightenerFactories = new ArrayList<IsolatedPixelWeightenerFactory<?>>();
		final ArrayList<CombinedPixelWeightenerFactory<?>> combinedWeightenerFactories = new ArrayList<CombinedPixelWeightenerFactory<?>>();
		
		if (conf.useEntropy)
			isolatedWeightenerFactories.add( new EntropyFastFactory( conf.processImageFactory ) );

		if (conf.useGaussContentBased)
			isolatedWeightenerFactories.add( new GaussContentFactory( conf.processImageFactory ) );

		if (conf.useIntegralContentBased)
			isolatedWeightenerFactories.add( new AverageContentFactory( conf.processImageFactory ) );

		if (conf.useLinearBlening)
		{
			// if we deconvolve we want a small border of black around the sample due to the PSF overlap
			if ( conf.isDeconvolution )
				combinedWeightenerFactories.add( new BlendingSimpleFactory( new double[] { 15, 15, 15 }, 0.3 ) );
			else
				combinedWeightenerFactories.add( new BlendingSimpleFactory( 0, 0.3 ) );
		}
		
		IOFunctions.println( "Fused image container: " + conf.processImageFactory.getClass().getSimpleName() );

		if ( conf.isDeconvolution && conf.deconvolutionLoadSequentially )
			fusion = new PreDeconvolutionFusionSequential( viewStructure, referenceViewStructure, isolatedWeightenerFactories, combinedWeightenerFactories );
		else if ( conf.isDeconvolution )
			fusion = new PreDeconvolutionFusion( viewStructure, referenceViewStructure, isolatedWeightenerFactories, combinedWeightenerFactories );
		else if (conf.multipleImageFusion)
			fusion = new MappingFusionSequentialDifferentOutput( viewStructure, referenceViewStructure, isolatedWeightenerFactories, combinedWeightenerFactories, conf.numParalellViews );
		else if (conf.paralellFusion)
			fusion = new MappingFusionParalell( viewStructure, referenceViewStructure, isolatedWeightenerFactories, combinedWeightenerFactories ); //TODO: Remove Max Weight
		else
		{
			if ( conf.numParalellViews >= viewStructure.getNumViews() )
				fusion = new MappingFusionParalell( viewStructure, referenceViewStructure, isolatedWeightenerFactories, combinedWeightenerFactories );
			else
				fusion = new MappingFusionSequential( viewStructure, referenceViewStructure, isolatedWeightenerFactories, combinedWeightenerFactories, conf.numParalellViews );
		}
				
		for ( int channelIndex = 0; channelIndex < viewStructure.getNumChannels(); ++channelIndex )
		{
			final int channelID = viewStructure.getChannelNum( channelIndex );
			
			boolean contains = false;
			for ( final int cF : conf.channelsFuse )
				if ( cF == channelID )
					contains = true;
			if ( !contains )
				continue;
			
			fusion.fuseSPIMImages( channelIndex );		
			
			if ( conf.isDeconvolution )
				return;
			
			if (conf.showOutputImage)
			{
				if ( !conf.multipleImageFusion )
				{
					if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
						IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Displaying image (Channel " + channelIndex +  ").");
					
					fusion.getFusedImage().getDisplay().setMinMax();
					
					String name = viewStructure.getSPIMConfiguration().inputFilePattern;			
					String replaceTP = SPIMConfiguration.getReplaceStringTimePoints( name );
					String replaceChannel = SPIMConfiguration.getReplaceStringChannels( name );
					
					if ( replaceTP != null )
						name = name.replace( replaceTP, "" + timePoint );

					if ( replaceChannel != null )
						name = name.replace( replaceChannel, "" + channelID );

					fusion.getFusedImage().setName( "Fused_" + name );
					
					ImageJFunctions.copyToImagePlus( fusion.getFusedImage() ).show();
					//fusion.getFusedImageVirtual().show();
				}
				else
				{	
					if ( channelIndex == 0 )
					{
						MappingFusionSequentialDifferentOutput multipleFusion = (MappingFusionSequentialDifferentOutput)fusion;
						
						int i = 0;
						
						for ( final ViewDataBeads view : viewStructure.getViews() )
						{
							final Image<FloatType> fused = multipleFusion.getFusedImage( i++ );
		
							String name = viewStructure.getSPIMConfiguration().inputFilePattern;			
							String replaceTP = SPIMConfiguration.getReplaceStringTimePoints( name );
							String replaceAngle = SPIMConfiguration.getReplaceStringAngle( name );
							String replaceChannel = SPIMConfiguration.getReplaceStringChannels( name );
							
							try
							{
								name = name.replace( replaceAngle, "" + view.getAcqusitionAngle() );
							}
							catch (Exception e ){};
		
							try
							{
								name = name.replace( replaceTP, "" + timePoint );
								name = name.replace( replaceChannel, "" + channelID );
							}
							catch (Exception e ){};
							
							fused.setName( name );
							fused.getDisplay().setMinMax();
							
							ImageJFunctions.copyToImagePlus( fused ).show();
							fused.close();
						}
					}
				}
			}
			
			if ( conf.writeOutputImage == 1)
			{			
				if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
					IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Writing output file (Channel " + channelIndex +  ").");
				
				fusion.saveAsTiffs( conf.outputdirectory, "img_tl" + timePoint, channelIndex );
			}
			else if ( conf.writeOutputImage == 2 )
			{
				final File dir = new File( conf.outputdirectory, "" + timePoint );
				if ( !dir.exists() && !dir.mkdirs() )
				{
					IOFunctions.printErr("(" + new Date(System.currentTimeMillis()) + "): Cannot create directory '" + dir.getAbsolutePath() + "', quitting.");
					return;
				}
				fusion.saveAsTiffs( dir.getAbsolutePath(), "img_tl" + timePoint, channelIndex );
			}
			
		}
		
		if  ( !conf.isDeconvolution )
			fusion.closeImages();
	}
	
}
