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
package spim.process.fusion.weightedavg;

import java.util.concurrent.Callable;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.img.Img;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import spim.process.fusion.FusionHelper;
import spim.process.fusion.ImagePortion;
import spim.process.fusion.boundingbox.BoundingBoxGUI;

/**
 * Fuse one portion of a paralell fusion, supports no weights
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 * @param <T>
 */
public class ProcessIndependentPortion< T extends RealType< T > > implements Callable< String >
{
	final ImagePortion portion;
	final RandomAccessibleInterval< T > img;
	final InterpolatorFactory<T, RandomAccessible< T > > interpolatorFactory;
	final AffineTransform3D transform;
	final Img< T > fusedImg;
	final BoundingBoxGUI bb;
	
	final boolean doDownSampling;
	final int downSampling;
	
	public ProcessIndependentPortion(
			final ImagePortion portion,
			final RandomAccessibleInterval< T > img,
			final InterpolatorFactory<T, RandomAccessible< T > > interpolatorFactory,
			final AffineTransform3D transform,
			final Img< T > fusedImg,
			final BoundingBoxGUI bb )
	{
		this.portion = portion;
		this.img = img;
		this.interpolatorFactory = interpolatorFactory;
		this.transform = transform;
		this.fusedImg = fusedImg;
		this.bb = bb;
		this.downSampling = bb.getDownSampling();
		
		if ( downSampling == 1 )
			doDownSampling = false;
		else
			doDownSampling = true;
	}
	
	@Override
	public String call() throws Exception 
	{
		// make the interpolators and get the transformations
		final RealRandomAccess< T > r = Views.interpolate( Views.extendMirrorSingle( img ), interpolatorFactory ).realRandomAccess();
		final int[] imgSize = new int[]{ (int)img.dimension( 0 ), (int)img.dimension( 1 ), (int)img.dimension( 2 ) };

		final Cursor< T > cursor = fusedImg.localizingCursor();
		final float[] s = new float[ 3 ];
		final float[] t = new float[ 3 ];
		
		cursor.jumpFwd( portion.getStartPosition() );
		
		for ( int j = 0; j < portion.getLoopSize(); ++j )
		{
			// move img cursor forward any get the value (saves one access)
			final T v = cursor.next();
			cursor.localize( s );
			
			if ( doDownSampling )
			{
				s[ 0 ] *= downSampling;
				s[ 1 ] *= downSampling;
				s[ 2 ] *= downSampling;
			}
			
			s[ 0 ] += bb.min( 0 );
			s[ 1 ] += bb.min( 1 );
			s[ 2 ] += bb.min( 2 );
			
			transform.applyInverse( t, s );
			
			if ( FusionHelper.intersects( t[ 0 ], t[ 1 ], t[ 2 ], imgSize[ 0 ], imgSize[ 1 ], imgSize[ 2 ] ) )
			{
				r.setPosition( t );
				v.setReal( r.get().getRealFloat() );
			}
		}
		
		return portion + " finished successfully (individual fusion, no weights).";
	}
}
