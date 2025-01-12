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

import ij.ImagePlus;

import java.util.ArrayList;

import spim.vecmath.Point3f;

import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.display.imagej.ImageJFunctions;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.AbstractAffineModel3D;
import mpicbg.spim.io.IOFunctions;
import mpicbg.spim.io.ImgLibSaver;
import mpicbg.spim.io.SPIMConfiguration;
import mpicbg.spim.registration.ViewDataBeads;
import mpicbg.spim.registration.ViewStructure;

public abstract class SPIMImageFusion
{
	protected SPIMConfiguration conf;
	final protected ArrayList <IsolatedPixelWeightenerFactory<?>> isolatedWeightenerFactories;
	final protected ArrayList <CombinedPixelWeightenerFactory<?>> combinedWeightenerFactories;
	protected Point3f min = null, max = null, size = null, location000 = null;	
	protected int cropOffsetX, cropOffsetY, cropOffsetZ, imgW, imgH, imgD, scale;
	
	final protected ViewStructure viewStructure;
		
	public SPIMImageFusion( ViewStructure viewStructure, ViewStructure referenceViewStructure, 
			 			    ArrayList<IsolatedPixelWeightenerFactory<?>> isolatedWeightenerFactories, 
			 			    ArrayList<CombinedPixelWeightenerFactory<?>> combinedWeightenerFactories )
	{
		this.conf = viewStructure.getSPIMConfiguration();
		this.viewStructure = viewStructure;
		this.scale = conf.scale;
		this.isolatedWeightenerFactories = isolatedWeightenerFactories;
		this.combinedWeightenerFactories = combinedWeightenerFactories;
		
		// compute the final image size
		computeFinalImageSize( referenceViewStructure.getViews() );
		
		// compute cropped image size
		initFusion();
		
		// compute location of point (0,0,0) in the global coordinate system
		location000 = new Point3f();
		location000.x = cropOffsetX * scale + min.x;
		location000.y = cropOffsetY * scale + min.y;
		location000.z = cropOffsetZ * scale + min.z;
		
		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println( "Location of pixel (0,0,0) in global coordinates is: " + location000 );
	}
	
	public abstract void fuseSPIMImages( int channelIndex );	
	public abstract Image<FloatType> getFusedImage();
	
	public ImagePlus getFusedImageCopy() { return ImageJFunctions.copyToImagePlus( getFusedImage() ); } 
	public ImagePlus getFusedImageVirtual() { return ImageJFunctions.displayAsVirtualStack( getFusedImage() ); } 
	public void closeImages() { getFusedImage().close(); }
	public boolean saveAsTiffs( final String dir, final String name, final int channelIndex ) { return ImgLibSaver.saveAsTiffs( getFusedImage(), dir, name + "_ch" + viewStructure.getChannelNum( channelIndex ), ImageJFunctions.GRAY32 ); }  
	
	public Point3f getOutputImageMinCoordinate() { return min; }
	public Point3f getOutputImageMaxCoordinate() { return max; }
	public Point3f getOutputImageSize() { return size; }

	protected void initFusion()
	{
		cropOffsetX = conf.cropOffsetX/scale;
		cropOffsetY = conf.cropOffsetY/scale;
		cropOffsetZ = conf.cropOffsetZ/scale;

		if (conf.cropSizeX == 0)
			imgW = (Math.round((float)Math.ceil(size.x)) + 1)/scale;
		else
			imgW = conf.cropSizeX/scale;
		
		if (conf.cropSizeY == 0)
			imgH = (Math.round((float)Math.ceil(size.y)) + 1)/scale;
		else
			imgH = conf.cropSizeY/scale;

		if (conf.cropSizeZ == 0)
			imgD = (Math.round((float)Math.ceil(size.z)) + 1)/scale;
		else
			imgD = conf.cropSizeZ/scale;	
	}
	
	public void computeFinalImageSize( final ArrayList <ViewDataBeads> views )
	{
		min = new Point3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
		max = new Point3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
		size = new Point3f();
	
		computeImageSize( views, min, max, size, conf.scale, conf.cropSizeX, conf.cropSizeY, conf.cropSizeZ, viewStructure.getDebugLevel() );
	}
		
	public static void computeImageSize( final ArrayList <ViewDataBeads> views, final Point3f min, final Point3f max, final Point3f size, final int scale, 
									     final int cropSizeX, final int cropSizeY, final int cropSizeZ, final int debugLevel )
	{
		min.x = Float.MAX_VALUE;
		min.y = Float.MAX_VALUE;
		min.z = Float.MAX_VALUE;
		
		max.x = -Float.MAX_VALUE;
		max.y = -Float.MAX_VALUE;
		max.z = -Float.MAX_VALUE;
				
		for ( final ViewDataBeads view : views )
		{			
			if ( Math.max( view.getViewErrorStatistics().getNumConnectedViews(), view.getTile().getConnectedTiles().size() ) <= 0 && view.getViewStructure().getNumViews() > 1 )
			{
				if ( view.getUseForRegistration() == true )
					if ( debugLevel <= ViewStructure.DEBUG_ERRORONLY )
						IOFunctions.printErr( "Cannot use view " + view + ", it is not connected to any other view!" );
				continue;
			}
			else if ( view.getViewStructure().getNumViews() == 1 )
			{
				if ( debugLevel <= ViewStructure.DEBUG_ERRORONLY )
					IOFunctions.printErr( "Warning: Only one view given: " + view );				
			}
			
			final int[] dim = view.getImageSize();			
			
			// transform the corner points of the current view
			final double[] minCoordinate = new double[]{ 0, 0, 0 };
			final double[] maxCoordinate = new double[]{ dim[0], dim[1], dim[2] };
			
			((AbstractAffineModel3D<?>)view.getTile().getModel()).estimateBounds( minCoordinate, maxCoordinate );

			min.x = (float)Math.min( minCoordinate[ 0 ], min.x );
			min.y = (float)Math.min( minCoordinate[ 1 ], min.y );
			min.z = (float)Math.min( minCoordinate[ 2 ], min.z );

			max.x = (float)Math.max( maxCoordinate[ 0 ], max.x );
			max.y = (float)Math.max( maxCoordinate[ 1 ], max.y );
			max.z = (float)Math.max( maxCoordinate[ 2 ], max.z );
		}
		
		size.sub(max, min);
		
		if ( debugLevel <= ViewStructure.DEBUG_MAIN )
		{
			IOFunctions.println("Dimension of final output image:");
			IOFunctions.println("From : " + min + " to " + max);
			double ram = (4l * size.x * size.y * size.z)/(1024l * 1024l);
			IOFunctions.println("Size: " + size + " needs " + Math.round( ram ) + " MB of RAM" );
			
			if ( scale != 1 )
			{
				ram = (4l * size.x/scale * size.y/scale * size.z/scale)/(1024l * 1024l);
				
				if ( views.get( 0 ).getViewStructure().getSPIMConfiguration().isDeconvolution )
					IOFunctions.println("Scaled size("+scale+"): (" + Math.round(size.x/scale) + ", " + Math.round(size.y/scale) + ", " + Math.round(size.z/scale) + ") needs " + Math.round( ram ) + " MB of RAM x " + 2*views.size() + " = " + Math.round( ram )*2*views.size() + " MB" );
				else
					IOFunctions.println("Scaled size("+scale+"): (" + Math.round(size.x/scale) + ", " + Math.round(size.y/scale) + ", " + Math.round(size.z/scale) + ") needs " + Math.round( ram ) + " MB of RAM" );
			}
			
			if ( cropSizeX > 0 && cropSizeY > 0 && cropSizeZ > 0)
			{
				if (scale != 1 )
					IOFunctions.println("Cropped & scaled("+scale+") image size: " + cropSizeX/scale + "x" + cropSizeY/scale + "x" + cropSizeZ/scale);
				else
					IOFunctions.println("Cropped image size: " + cropSizeX + "x" + cropSizeY + "x" + cropSizeZ);
				
				ram = (4l * cropSizeX/scale * cropSizeY/scale * cropSizeZ/scale)/(1024l * 1024l);
				
				if ( views.get( 0 ).getViewStructure().getSPIMConfiguration().isDeconvolution )
					IOFunctions.println("Needs " + Math.round( ram ) + " MB of RAM x " + 2*views.size() + " = " + Math.round( ram )*2*views.size() + " MB" );
				else
					IOFunctions.println("Needs " + Math.round( ram ) + " MB of RAM");
			}
		}
	}
	
}
