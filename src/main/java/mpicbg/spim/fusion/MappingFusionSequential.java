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

import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import spim.vecmath.Point3d;

import mpicbg.imglib.cursor.Cursor;
import mpicbg.imglib.cursor.LocalizableByDimCursor;
import mpicbg.imglib.cursor.LocalizableCursor;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.ImageFactory;
import mpicbg.imglib.interpolation.Interpolator;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.imglib.util.Util;
import mpicbg.models.AbstractAffineModel3D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.spim.io.IOFunctions;
import mpicbg.spim.registration.ViewDataBeads;
import mpicbg.spim.registration.ViewStructure;

public class MappingFusionSequential extends SPIMImageFusion
{
	final Image<FloatType> fusedImage, weights;
	final int numParalellStacks;

	public MappingFusionSequential( final ViewStructure viewStructure, final ViewStructure referenceViewStructure,
			  						final ArrayList<IsolatedPixelWeightenerFactory<?>> isolatedWeightenerFactories,
			  						final ArrayList<CombinedPixelWeightenerFactory<?>> combinedWeightenerFactories,
			  						final int numParalellStacks )
	{
		super( viewStructure, referenceViewStructure, isolatedWeightenerFactories, combinedWeightenerFactories );

		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Reserving memory for fused image.");

		final ImageFactory<FloatType> fusedImageFactory = new ImageFactory<FloatType>( new FloatType(), conf.processImageFactory );

		fusedImage = fusedImageFactory.createImage( new int[]{ imgW, imgH, imgD }, "Fused image");
		weights = fusedImageFactory.createImage( new int[]{ imgW, imgH, imgD }, "Weights image");
		this.numParalellStacks = numParalellStacks;

		if ( fusedImage == null )
		{
			if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_ERRORONLY )
				IOFunctions.println("MappingFusionSequentialImages.constructor: Cannot create output image: " + conf.processImageFactory.getErrorMessage());

			if ( weights != null )
				weights.close();

			return;
		}

		if ( weights == null )
		{
			if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_ERRORONLY )
				IOFunctions.println("MappingFusionSequentialImages.constructor: Cannot create weights image: " + conf.processImageFactory.getErrorMessage());

			fusedImage.close();

			return;
		}
	}

	@Override
	public void fuseSPIMImages( final int channelIndex )
	{
		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Unloading source images.");

		// unload images
		for ( final ViewDataBeads view : viewStructure.getViews() )
			view.closeImage();

		// clear the previous output image
		if ( channelIndex > 0 )
		{
			final Cursor<FloatType> iteratorFused = fusedImage.createCursor();
			final Cursor<FloatType> iteratorWeights = weights.createCursor();

			// compute final image
			while ( iteratorFused.hasNext() )
			{
				iteratorFused.fwd();
				iteratorWeights.fwd();

				iteratorFused.getType().set( 0 );
				iteratorWeights.getType().set( 0 );
			}

			iteratorFused.close();
			iteratorWeights.close();
		}

		//
		// update views so that only the current channel is being fused
		//
		final ArrayList<ViewDataBeads> views = new ArrayList<ViewDataBeads>();

		for ( final ViewDataBeads view : viewStructure.getViews() )
			if ( view.getChannelIndex() == channelIndex )
				views.add( view );

		final int numViews = views.size();

		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Computing output image (Channel " + channelIndex +  ").");

		// cache the views, imageSizes and models that we use
		final boolean useView[] = new boolean[ numViews ];
		final AbstractAffineModel3D<?> models[] = new AbstractAffineModel3D[ numViews ];

		for ( int i = 0; i < numViews; ++i )
		{
			useView[ i ] = Math.max( views.get( i ).getViewErrorStatistics().getNumConnectedViews(), views.get( i ).getTile().getConnectedTiles().size() ) > 0 || views.get( i ).getViewStructure().getNumViews() == 1;

			// if a corresponding view that was used for registration is valid, this one is too
			if ( views.get( i ).getUseForRegistration() == false )
			{
				final int angle = views.get( i ).getAcqusitionAngle();
				final int timepoint = views.get( i ).getViewStructure().getTimePoint();

				for ( final ViewDataBeads view2 : viewStructure.getViews() )
					if ( view2.getAcqusitionAngle() == angle && timepoint == view2.getViewStructure().getTimePoint() && view2.getUseForRegistration() == true )
						useView[ i ] = true;
			}

			models[ i ] = (AbstractAffineModel3D<?>)views.get( i ).getTile().getModel();
		}

		final int[][] imageSizes = new int[numViews][];
		for ( int i = 0; i < numViews; ++i )
			imageSizes[ i ] = views.get( i ).getImageSize();

		// iterate over input images in steps of numParalellStacks
		for (int v = 0; v < numViews; v += numParalellStacks )
		{
			final int viewIndexStart = v;
			final int viewIndexEnd = Math.min( v + numParalellStacks, numViews );

			// the views we are processing in this run
			final ArrayList<ViewDataBeads> processViews = new ArrayList<ViewDataBeads>();

			for ( int viewIndex = viewIndexStart; viewIndex < viewIndexEnd; ++viewIndex )
				processViews.add( views.get( viewIndex ) );

			final int startView, endView;
			if (combinedWeightenerFactories.size() > 0)
			{
				startView = 0;
				endView = numViews;
			}
			else
			{
				startView = viewIndexStart;
				endView = viewIndexEnd;
			}

			// load the current images
			for ( final ViewDataBeads view : processViews  )
			{
				if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
					IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Loading view: " + view.getName() );

				view.getImage( conf.inputImageFactory, false );
			}

			if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN && isolatedWeightenerFactories.size() > 0 )
			{
				String methods = "(" + isolatedWeightenerFactories.get(0).getDescriptiveName();
				for ( int i = 1; i < isolatedWeightenerFactories.size(); ++i )
					methods += ", " + isolatedWeightenerFactories.get(i).getDescriptiveName();
				methods += ")";

				IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Init isolated weighteners for views " + viewIndexStart + " to " + (viewIndexEnd-1) + ": " + methods );
			}

			// init isolated pixel weighteners
			final AtomicInteger ai = new AtomicInteger(0);
	        Thread[] threads = SimpleMultiThreading.newThreads(conf.numberOfThreads);
	        final int numThreads = threads.length;

	        // compute them all in paralell ( computation done while opening )
			IsolatedPixelWeightener<?>[][] isoWinit = new IsolatedPixelWeightener<?>[ isolatedWeightenerFactories.size() ][ numViews ];
			for (int j = 0; j < isoWinit.length; j++)
			{
				final int i = j;

				final IsolatedPixelWeightener<?>[][] isoW = isoWinit;

				for (int ithread = 0; ithread < threads.length; ++ithread)
		            threads[ithread] = new Thread(new Runnable()
		            {
		                @Override
						public void run()
		                {
		                	final int myNumber = ai.getAndIncrement();

							for ( int view = viewIndexStart; view < viewIndexEnd; view++ )
								if ( view % numThreads == myNumber)
								{
									IOFunctions.println( "Computing " + isolatedWeightenerFactories.get( i ).getDescriptiveName() + " for " + views.get( view ) );
									isoW[i][view] = isolatedWeightenerFactories.get(i).createInstance( views.get( view ) );
								}
		                }
		            });

				SimpleMultiThreading.startAndJoin( threads );
			}

			// test if the isolated weighteners were successfull...
			try
			{
				boolean successful = true;

				for ( final IsolatedPixelWeightener[] iso : isoWinit )
					for ( int view = viewIndexStart; view < viewIndexEnd; view++ )
						if ( iso[ view ] == null )
							successful = false;

				if ( !successful )
				{
					IOFunctions.println( "WARNING: Not enough memory for running the content-based fusion, running without it" );
					isoWinit = new IsolatedPixelWeightener[ 0 ][ 0 ];
				}
			}
			catch (final Exception e)
			{
				IOFunctions.println( "WARNING: Not enough memory for running the content-based fusion, running without it" );
				isoWinit = new IsolatedPixelWeightener[ 0 ][ 0 ];
			}

			// unnormalize all views prior to starting the fusion (otherwise it might be called more than once due to multi-threading)
			for (int view = viewIndexStart; view < viewIndexEnd; view++)
				views.get( view ).getImage( false );

			final IsolatedPixelWeightener<?>[][] isoW = isoWinit;

			ai.set( 0 );
	        threads = SimpleMultiThreading.newThreads( numThreads );

			for (int ithread = 0; ithread < threads.length; ++ithread)
	            threads[ithread] = new Thread(new Runnable()
	            {
	                @Override
					public void run()
	                {
	                	try
	                	{
		                	final int myNumber = ai.getAndIncrement();

		                	// temporary float array
		                	final double[] tmp = new double[ 3 ];

			        		// init combined pixel weighteners
			        		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN && combinedWeightenerFactories.size() > 0 && myNumber == 0 )
			        		{
			        			String methods = "(" + combinedWeightenerFactories.get(0).getDescriptiveName();
			        			for ( int i = 1; i < combinedWeightenerFactories.size(); ++i )
			        				methods += ", " + combinedWeightenerFactories.get(i).getDescriptiveName();
			        			methods += ")";

			        			IOFunctions.println( "Initialize combined weighteners for for views " + viewIndexStart + " to " + (viewIndexEnd-1) + " " + methods + " (" + numThreads + " threads)" );
			        		}

			        		final CombinedPixelWeightener<?>[] combW = new CombinedPixelWeightener<?>[combinedWeightenerFactories.size()];
			        		for (int i = 0; i < combW.length; i++)
			        			combW[i] = combinedWeightenerFactories.get(i).createInstance( views );

							// get iterators for isolated weights
			        		final LocalizableByDimCursor<FloatType> isoIterators[][] = new LocalizableByDimCursor[ isoW.length ][ numViews ];
							for (int i = 0; i < isoW.length; i++)
								for (int view = viewIndexStart; view < viewIndexEnd; view++)
									isoIterators[i][view] = isoW[i][view].getResultIterator();

			    			// create Interpolated Iterators for the input images (every thread need own ones!)
			    			final Interpolator<FloatType>[] interpolators = new Interpolator[ numViews ];
							for (int view = viewIndexStart; view < viewIndexEnd; view++)
			    				interpolators[ view ] = views.get( view ).getImage( false ).createInterpolator( conf.interpolatorFactorOutput );

							final Point3d[] tmpCoordinates = new Point3d[ numViews ];
							final int[][] loc = new int[ numViews ][ 3 ];
			    			final double[][] locf = new double[ numViews ][ 3 ];
							final boolean[] use = new boolean[ numViews ];

							for (int i = 0; i < tmpCoordinates.length; i++)
								tmpCoordinates[i] = new Point3d();

							if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN && myNumber == 0 )
								for ( final ViewDataBeads view : processViews )
									IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Starting fusion for: " + view.getName() );

							final LocalizableCursor<FloatType> iteratorFused = fusedImage.createLocalizableCursor();
							final LocalizableCursor<FloatType> iteratorWeights = weights.createLocalizableCursor();

							while (iteratorFused.hasNext())
							{
								iteratorFused.next();
								iteratorWeights.next();

								if (iteratorFused.getPosition( 2 ) % numThreads == myNumber)
								{
									// get the coordinates if cropped
									final int x = iteratorFused.getPosition(0) + cropOffsetX;
									final int y = iteratorFused.getPosition(1) + cropOffsetY;
									final int z = iteratorFused.getPosition(2) + cropOffsetZ;

									int num = 0;
									for (int i = startView; i < endView; ++i)
									{
										if ( useView[ i ])
										{
											tmpCoordinates[i].x = x * scale + min.x;
											tmpCoordinates[i].y = y * scale + min.y;
											tmpCoordinates[i].z = z * scale + min.z;

		        							mpicbg.spim.mpicbg.Java3d.applyInverseInPlace( models[i], tmpCoordinates[i], tmp );

		        							loc[i][0] = (int)Util.round( tmpCoordinates[i].x );
		        							loc[i][1] = (int)Util.round( tmpCoordinates[i].y );
		        							loc[i][2] = (int)Util.round( tmpCoordinates[i].z );

			    							locf[i][0] = tmpCoordinates[i].x;
			    							locf[i][1] = tmpCoordinates[i].y;
			    							locf[i][2] = tmpCoordinates[i].z;

			    							// do we hit the source image?
											if ( loc[ i ][ 0 ] >= 0 && loc[ i ][ 1 ] >= 0 && loc[ i ][ 2 ] >= 0 &&
												 loc[ i ][ 0 ] < imageSizes[ i ][ 0 ] &&
												 loc[ i ][ 1 ] < imageSizes[ i ][ 1 ] &&
												 loc[ i ][ 2 ] < imageSizes[ i ][ 2 ] )
			    							{
			    								use[i] = true;
			    								if ( i >= viewIndexStart && i < viewIndexEnd )
			    									++num;
			    							}
			    							else
			    							{
			    								use[i] = false;
			    							}
										}
										else
										{
											use[i] = false;
										}
									}

									if ( num > 0 )
									{
										// update combined weighteners
										if (combW.length > 0)
											for (final CombinedPixelWeightener<?> w : combW)
												w.updateWeights( locf, use );

			    						for ( int view = viewIndexStart; view < viewIndexEnd; ++view )
			    							if ( use[view] )
			    							{
												float weight = 1;

												// multiplicate combined weights
												if (combW.length > 0)
													for (final CombinedPixelWeightener<?> w : combW)
														weight *= w.getWeight( view );

			    								// multiplicate isolated weights
			    								for (int i = 0; i < isoW.length; i++)
			    								{
			    									isoIterators[ i ][ view ].setPosition( loc[ view ] );
													weight *= isoIterators[ i ][ view ].getType().get();
			    								}

			    								tmp[ 0 ] = tmpCoordinates[ view ].x;
			    								tmp[ 1 ] = tmpCoordinates[ view ].y;
			    								tmp[ 2 ] = tmpCoordinates[ view ].z;

			    								interpolators[ view ].moveTo( tmp );

												final float intensity = interpolators[ view ].getType().get();
												final float value = weight * intensity;


												iteratorWeights.getType().set( iteratorWeights.getType().get() + weight );
												iteratorFused.getType().set( iteratorFused.getType().get() + value );
			    							}
									}
								}
							}

							iteratorFused.close();
							iteratorWeights.close();

							// close iterators
							for ( int view = viewIndexStart; view < viewIndexEnd; ++view )
								interpolators[ view ].close();

		        			// close isolated iterators
							for (int i = 0; i < isoW.length; i++)
								for( int view = viewIndexStart; view < viewIndexEnd; ++view )
									isoIterators[i][view].close();

							// close weighteners
							// close combined pixel weighteners
							for (int i = 0; i < combW.length; i++)
								combW[i].close();
	                	}
	                	catch (final NoninvertibleModelException e)
	                	{
	                		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_ERRORONLY )
	                			IOFunctions.println( "MappingFusionParalell(): Model not invertible for " + viewStructure );
	                	}
	                }// Thread.run loop
	            });

	        SimpleMultiThreading.startAndJoin(threads);

			// unload input image
			for ( int view = viewIndexStart; view < viewIndexEnd; ++view )
				views.get( view ).closeImage();

			// unload isolated weightener
			try
			{
				for (int i = 0; i < isoW.length; i++)
					for ( int view = viewIndexStart; view < viewIndexEnd; ++view )
						isoW[ i ][ view ].close();
			}
			catch (final Exception e )
			{
				// this will fail if there was not enough memory...
			}

		}// input images

		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println("Computing final output image (Channel " + channelIndex +  ").");

		final Cursor<FloatType> iteratorFused = fusedImage.createCursor();
		final Cursor<FloatType> iteratorWeights = weights.createCursor();

		// compute final image
		while (iteratorFused.hasNext())
		{
			iteratorFused.fwd();
			iteratorWeights.fwd();
			final float weight = iteratorWeights.getType().get();

			if (weight > 0)
				iteratorFused.getType().set( iteratorFused.getType().get()/weight );
		}

		iteratorFused.close();
		iteratorWeights.close();

		if ( viewStructure.getDebugLevel() <= ViewStructure.DEBUG_MAIN )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Done computing output image (Channel " + channelIndex +  ").");
	}

	@Override
	public Image<FloatType> getFusedImage() { return fusedImage; }

	@Override
	public void closeImages()
	{
		fusedImage.close();
		weights.close();
	}

}
