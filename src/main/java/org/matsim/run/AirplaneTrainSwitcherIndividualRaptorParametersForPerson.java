package org.matsim.run;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorParametersForPerson;
import ch.sbb.matsim.routing.pt.raptor.RaptorUtils;
import com.google.inject.Inject;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

import java.util.Map;
import java.util.Random;

/**
 * An implementation of {@link RaptorParametersForPerson} that returns an
 * individual set of routing parameters, based on
 * {@link ScoringParametersForPerson}.
 *
 * @author sebhoerl / ETHZ
 */
public class AirplaneTrainSwitcherIndividualRaptorParametersForPerson implements RaptorParametersForPerson {
	private final Config config;
	private final SwissRailRaptorConfigGroup raptorConfig;
	private final ScoringParametersForPerson parametersForPerson;
	private final Random random;


	@Inject
	public AirplaneTrainSwitcherIndividualRaptorParametersForPerson(Config config, ScoringParametersForPerson parametersForPerson) {
		this.config = config;
		this.raptorConfig = ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);
		this.parametersForPerson = parametersForPerson;
		this.random = MatsimRandom.getLocalInstance();
	}

	@Override
	public RaptorParameters getRaptorParameters(Person person) {
		RaptorParameters raptorParameters = RaptorUtils.createParameters(config);
		ScoringParameters scoringParameters = parametersForPerson.getScoringParameters(person);

		double marginalUtilityOfPerforming = scoringParameters.marginalUtilityOfPerforming_s;

		raptorParameters.setMarginalUtilityOfWaitingPt_utl_s(
				scoringParameters.marginalUtilityOfWaitingPt_s - marginalUtilityOfPerforming);

		ScoringConfigGroup pcsConfig = config.scoring();

		for (Map.Entry<String, ScoringConfigGroup.ModeParams> e : pcsConfig.getModes().entrySet()) {
			String mode = e.getKey();
			ModeUtilityParameters modeParams = scoringParameters.modeParams.get(mode);

			if (modeParams != null) {
//				if (mode.equals(TransportMode.airplane) && random.nextBoolean()) {
				if ((mode.equals(TransportMode.airplane) || mode.equals("longDistanceTrain")) && random.nextBoolean()) {
					// our addition: switch off by random usage of airplane
					raptorParameters.setMarginalUtilityOfTravelTime_utl_s(mode,
							1000000.0d * (modeParams.marginalUtilityOfTraveling_s - marginalUtilityOfPerforming));
//					if (mode.equals(TransportMode.airplane)) {
//						log.fatal("switching off airplane by setting very high MarginalUtilityOfTravelTime_utl_s");
//					}
//					if (mode.equals(TransportMode.train)) {
//						log.fatal("switching off train by setting very high MarginalUtilityOfTravelTime_utl_s");
//					}
				} else {
					// default: copy from PlanCalcScoreConfigGroup
					raptorParameters.setMarginalUtilityOfTravelTime_utl_s(mode,
							modeParams.marginalUtilityOfTraveling_s - marginalUtilityOfPerforming);
				}
			}
		}

		double costPerHour = this.raptorConfig.getTransferPenaltyCostPerTravelTimeHour();
		if (costPerHour == 0.0) {
			// backwards compatibility, use the default utility of line switch
			raptorParameters.setTransferPenaltyFixCostPerTransfer(-scoringParameters.utilityOfLineSwitch);
		} else {
			raptorParameters.setTransferPenaltyFixCostPerTransfer(this.raptorConfig.getTransferPenaltyBaseCost());
		}
		raptorParameters.setTransferPenaltyPerTravelTimeHour(costPerHour);
		raptorParameters.setTransferPenaltyMinimum(this.raptorConfig.getTransferPenaltyMinCost());
		raptorParameters.setTransferPenaltyMaximum(this.raptorConfig.getTransferPenaltyMaxCost());

		return raptorParameters;
	}
}
