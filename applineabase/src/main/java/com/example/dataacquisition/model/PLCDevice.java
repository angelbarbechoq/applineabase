package com.example.dataacquisition.model;

import jakarta.persistence.*;
import org.jspecify.annotations.Nullable;

/**
 * PLC Device model for Siemens S7-200.
 */
@Entity
@Table(name = "plc_device")
public class PLCDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(name = "plc_id")
    private Long id;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    @Column(name = "name", nullable = false)
    private String name;

    protected PLCDevice() {
        // To keep Hibernate happy
    }

    public PLCDevice(String ipAddress, String name) {
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
        PLCDevice other = (PLCDevice) obj;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
