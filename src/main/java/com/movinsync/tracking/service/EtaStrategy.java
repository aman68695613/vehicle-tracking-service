package com.movinsync.tracking.service;

/**
 * Strategy interface for computing ETA from distance and speed.
 * Allows the speed model to be swapped (as required by the OOP design in the plan).
 */
public interface EtaStrategy {

    /**
     * Compute ETA in seconds.
     *
     * @param distanceMetres remaining distance along the route
     * @param speedMs        current effective speed in m/s
     * @param trafficFactor  multiplier for traffic conditions
     * @return ETA in seconds
     */
    double computeEtaSeconds(double distanceMetres, double speedMs, double trafficFactor);
}
