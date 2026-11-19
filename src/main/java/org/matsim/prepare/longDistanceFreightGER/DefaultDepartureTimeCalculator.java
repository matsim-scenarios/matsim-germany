package org.matsim.prepare.longDistanceFreightGER;

import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Default departure-time calculator for German long-distance freight agents.
 * <p>
 * Departure times are sampled from an hourly start-time distribution derived from
 * BASt 2023 heavy-vehicle count data. The input probabilities are prepared by
 * {@code CalculateStartTimesForLongDistanceTrips.R} and represent the expected
 * share of long-distance freight tours starting in each hour of the day.
 * The analysis currently assumes a 30-minute start shift and assigns observed
 * traffic volumes to plausible starts within a nine-hour tour window.
 * </p>
 * <p>
 * The calculator first samples an hourly interval according to this distribution
 * and then draws a uniformly distributed second within that interval. This keeps
 * the BASt-derived daily pattern while avoiding departures exactly on full-hour
 * boundaries.
 * </p>
 */
class DefaultDepartureTimeCalculator implements FreightAgentGenerator.DepartureTimeCalculator {
	private final RandomGenerator rnd = new MersenneTwister(4711);
	private final EnumeratedDistribution<DurationsBounds> longDistanceStartTimeDistribution;

	public DefaultDepartureTimeCalculator() {
		// Hourly weights are derived from the BASt long-distance freight start-time
		// analysis in CalculateStartTimesForLongDistanceTrips.R.
		// The distribution samples one one-hour interval; EnumeratedDistribution
		// normalizes the weights, so they do not have to sum to one here.
		List<Pair<DurationsBounds, Double>> longDistanceStartTimeDistributionBounds = new ArrayList<>();
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(0, 1), 0.0388254168523453));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(1, 2), 0.0438873470682074));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(2, 3), 0.0488922045279851));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(3, 4), 0.0535853768337898));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(4, 5), 0.0575570741626213));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(5, 6), 0.0602146906994415));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(6, 7), 0.0611531098376689));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(7, 8), 0.060672649437304));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(8, 9), 0.059194087445975));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(9, 10), 0.0567526875444281));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(10, 11), 0.0534399937238652));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(11, 12), 0.0494918016667157));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(12, 13), 0.0451276015756904));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(13, 14), 0.0404679896001495));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(14, 15), 0.035658101882499));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(15, 16), 0.0310139421807826));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(16, 17), 0.0269416178998621));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(17, 18), 0.0237761380830235));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(18, 19), 0.0217210571392102));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(19, 20), 0.021057043105208));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(20, 21), 0.0221801166161617));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(21, 22), 0.025154141369418));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(22, 23), 0.0293096183361158));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(23, 24), 0.0339261924115318));
		longDistanceStartTimeDistribution = new EnumeratedDistribution<>(rnd, longDistanceStartTimeDistributionBounds);

	}
    @Override
    public double getDepartureTime() {
		DurationsBounds durationBounds = longDistanceStartTimeDistribution.sample();
		return durationBounds.lowerBoundStartTime * 3600 + rnd.nextInt((durationBounds.upperBoundStartTime - durationBounds.lowerBoundStartTime) * 3600);
    }

	private record DurationsBounds(int lowerBoundStartTime, int upperBoundStartTime) {}
}
