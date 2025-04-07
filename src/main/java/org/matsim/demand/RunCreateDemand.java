/* *********************************************************************** *
 * project: org.matsim.*
 * EditRoutesTest.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */


package org.matsim.demand;

import java.net.MalformedURLException;

import org.matsim.api.core.v01.population.Population;

/**
* @author smueller
*/

public class RunCreateDemand {

	public static void main(String[] args) {
		
		boolean train = false;
		boolean car = true;
		boolean airplane = false;
		boolean pt = false;
		boolean bike = false;
		boolean walk = false;
		
		double sample = 0.001;
		
		String outputPopulationFile = "output/onlyCar" + getSampleNameOfOutputFolder(sample) + "pct.xml.gz";

		try {
			CreateDemand.create(outputPopulationFile, sample, train, car, airplane, pt, bike, walk);
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private static String getSampleNameOfOutputFolder(double sample) {
		String sampleName;
		if ((sample * 100) % 1 == 0)
			sampleName = String.valueOf((int) (sample * 100));
		else
			sampleName = String.valueOf((sample * 100));
		return sampleName;
	}

}
