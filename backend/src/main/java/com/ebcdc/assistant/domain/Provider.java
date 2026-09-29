package com.ebcdc.assistant.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "providers")
public class Provider {
    @Id
    private UUID id = UUID.randomUUID();
    private String name;
    @Column(name = "contract_ref")
    private String contractRef;
    @Column(name = "sla_days")
    private Integer slaDays;

    public Provider() {}
    public Provider(String name, String contractRef, Integer slaDays) {
        this.name = name; this.contractRef = contractRef; this.slaDays = slaDays;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getContractRef() { return contractRef; }
    public Integer getSlaDays() { return slaDays; }
}
