package com.example.dataacquisition.model;

import jakarta.persistence.*;
import org.jspecify.annotations.Nullable;

/**
 * PAS Device model for Schneider PAS600L meter.
 */
@Entity
@Table(name = "pas_device")
public class PASDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(name = "pas_id")
    private Long id;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    @Column(name = "name", nullable = false)
    private String name;

    protected PASDevice() {
        // To keep Hibernate happy
    }

    public PASDevice(String ipAddress, String name) {
        this.ipAddress = ipAddress;
        this.name = name;
    }

    public @Nullable Long getId() {
        return id;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !getClass().isAssignableFrom(obj.getClass())) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        PASDevice other = (PASDevice) obj;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
