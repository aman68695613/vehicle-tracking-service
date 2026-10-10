package com.movinsync.tracking.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pickup_requests")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PickupRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_stop_id", nullable = false)
    private RouteStop routeStop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private AppUser employee;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
