package com.movinsync.tracking.service;

import org.springframework.stereotype.Component;

/**
 * Default ETA strategy: eta = distance / speed * trafficFactor.
 */
@Component
public class DefaultEtaStrategy implements EtaStrategy {

    @Override
    public double computeEtaSeconds(double distanceMetres, double speedMs, double trafficFactor) {
        if (speedMs <= 0) {
            return Double.MAX_VALUE;
        }
        return (distanceMetres / speedMs) * trafficFactor;
    }
}
