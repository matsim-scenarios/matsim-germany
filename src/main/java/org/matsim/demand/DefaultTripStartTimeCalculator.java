package org.matsim.demand;

import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.RandomGeneratorFactory;
import org.apache.commons.math3.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Default trip-start-time calculator for the Germany demand population.
 * <p>
 * Trip start times depend on the destination activity type and use the distributions from MiD 2017, table A W7.
 * Since MiD does not contain a distribution for holidays, the distribution for all trips is used for holiday trips.
 * The {@code other} activity type represents leisure trips.
 * </p>
 */
class DefaultTripStartTimeCalculator implements CreateDemand.TripStartTimeCalculator {

	private static final List<TimeBounds> TIME_BOUNDS = List.of(
		new TimeBounds(0, 5),
		new TimeBounds(5, 8),
		new TimeBounds(8, 10),
		new TimeBounds(10, 13),
		new TimeBounds(13, 16),
		new TimeBounds(16, 19),
		new TimeBounds(19, 22)
	);

	private final Random random;
	private final Map<String, EnumeratedDistribution<TimeBounds>> distributions;

	DefaultTripStartTimeCalculator(Random random) {
		this.random = random;
		RandomGenerator randomGenerator = RandomGeneratorFactory.createRandomGenerator(random);
		this.distributions = Map.of(
			"work", createDistribution(randomGenerator, 0.04, 0.31, 0.10, 0.09, 0.18, 0.22, 0.06),
			"education", createDistribution(randomGenerator, 0.0, 0.36, 0.11, 0.11, 0.27, 0.12, 0.03),
			"shop", createDistribution(randomGenerator, 0.0, 0.03, 0.18, 0.32, 0.21, 0.21, 0.05),
			"business", createDistribution(randomGenerator, 0.02, 0.13, 0.18, 0.25, 0.22, 0.15, 0.05),
			"holiday", createDistribution(randomGenerator, 0.03, 0.11, 0.12, 0.20, 0.23, 0.23, 0.08),
			"other", createDistribution(randomGenerator, 0.05, 0.03, 0.08, 0.18, 0.24, 0.28, 0.14)
		);
	}

	@Override
	public int createTripStartTime(String nextActType) {
		EnumeratedDistribution<TimeBounds> distribution = distributions.get(nextActType);
		if (distribution == null) {
			random.nextDouble();
			return -1;
		}

		TimeBounds timeBounds = distribution.sample();
		int lowerBound = timeBounds.lowerBoundStartTime * 3600;
		int duration = (timeBounds.upperBoundStartTime - timeBounds.lowerBoundStartTime) * 3600;
		return lowerBound + (int) Math.round(-0.5 + duration * random.nextDouble());
	}

	private static EnumeratedDistribution<TimeBounds> createDistribution(
		RandomGenerator randomGenerator, double... distributionProbabilities
	) {
		List<Pair<TimeBounds, Double>> probabilities = new ArrayList<>();
		for (int i = 0; i < TIME_BOUNDS.size(); i++) {
			double probability = distributionProbabilities[i];
			if (probability > 0) {
				probabilities.add(Pair.create(TIME_BOUNDS.get(i), probability));
			}
		}
		return new EnumeratedDistribution<>(randomGenerator, probabilities);
	}

	/**
	 * Defines the lower and upper hour of a trip-start-time interval.
	 */
	private record TimeBounds(int lowerBoundStartTime, int upperBoundStartTime) {
	}
}
