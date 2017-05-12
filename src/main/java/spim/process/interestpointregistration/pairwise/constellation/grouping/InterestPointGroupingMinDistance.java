package spim.process.interestpointregistration.pairwise.constellation.grouping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.KDTree;
import net.imglib2.neighborsearch.RadiusNeighborSearch;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import spim.fiji.spimdata.interestpoints.InterestPoint;

/**
 * The simplest way to group interest points, just add them all (this will create problems in overlaps)
 * 
 * @author spreibi
 */
public class InterestPointGroupingMinDistance< V extends ViewId > extends InterestPointGrouping< V >
{
	public static double DEFAULT_RADIUS = 2.5;

	final double radius;

	public InterestPointGroupingMinDistance( final double radius, final Map< V, List< InterestPoint > > interestpoints )
	{
		super( interestpoints );

		this.radius = radius;
	}

	public InterestPointGroupingMinDistance( final Map< V, List< InterestPoint > > interestpoints )
	{
		this( DEFAULT_RADIUS, interestpoints );
	}

	public double getRadius() { return radius; }

	@Override
	protected List< GroupedInterestPoint< V > > merge( final Map< V, List< InterestPoint > > toMerge )
	{
		final ArrayList< GroupedInterestPoint< V > > grouped = InterestPointGroupingAll.mergeAll( toMerge );

		// pseudo-random shuffling to not give an advantage due to the order the views are in
		Collections.shuffle( grouped, new Random( 234 ) );

		//
		// make a list and a tree at the same time, use the tree to mark points in close proximity as false
		//

		// if a certain interestpoint is still valid
		// (grouped and markedPoints MUST be in the same order for this to work)
		final ArrayList< Pair< GroupedInterestPoint< V >, Bool > > markedPoints = new ArrayList<>();

		// all points are true initially, and will be set false if they were within the radius of a selected point
		for ( final GroupedInterestPoint< V > p : grouped )
			markedPoints.add( new ValuePair<>( p, new Bool( true ) ) );

		final KDTree< Pair< GroupedInterestPoint< V >, Bool > > tree = new KDTree<>( markedPoints, grouped );
		final RadiusNeighborSearch< Pair< GroupedInterestPoint< V >, Bool > > search =
				new RadiusNeighborSearchOnKDTree<>( tree );

		// go over all points
		for ( final Pair< GroupedInterestPoint< V >, Bool > p : markedPoints )
		{
			if ( p.getB().state )
			{
				// radius neighbor search
				search.search( p.getA(), radius, false );

				// make sure by comparing ViewId and Id that it is not the one we currently look at
				for ( int i = 0; i < search.numNeighbors(); ++i )
				{
					final Pair< GroupedInterestPoint< V >, Bool > neighbor = search.getSampler( i ).get();
					final GroupedInterestPoint< V > neighborpoint = neighbor.getA();

					if ( !neighborpoint.equals( p.getA() ) )// && !neighborpoint.getV().equals( p.getA().getV() ) )
					{
						neighbor.getB().state = false;
					}
				}
			}
		}

		grouped.clear();

		int[] hist = new int[ 7 ];
		int tp = -1;

		for ( final Pair< GroupedInterestPoint< V >, Bool > p : markedPoints )
			if ( p.getB().state )
			{
				grouped.add( p.getA() );
				++hist[ ((ViewId)(p.getA().getV())).getViewSetupId() ];
				tp = ((ViewId)(p.getA().getV())).getTimePointId();
			}

		System.out.println( "\ntp = " + tp );

		for ( int i = 0; i < hist.length; ++i )
			System.out.println( i + ": " + hist[ i ] );

		return grouped;
	}
}