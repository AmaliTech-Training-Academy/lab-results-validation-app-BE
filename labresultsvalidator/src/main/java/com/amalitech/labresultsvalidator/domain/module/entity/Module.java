package com.amalitech.labresultsvalidator.domain.module.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.entity.UserModuleAssignment;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "modules",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_module_name",
                        columnNames = {"specialization_id", "name"}),
                @UniqueConstraint(name = "uq_module_sequence",
                        columnNames = {"specialization_id", "sequence"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Module extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "specialization_id", nullable = false)
    private Specialization specialization;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @OneToMany(mappedBy = "module", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private List<Lab> labs;

    @OneToMany(mappedBy = "module", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private List<UserModuleAssignment> userModuleAssignments;
}