package com.amalitech.labresultsvalidator.domain.module.entity;

import com.amalitech.labresultsvalidator.common.BaseEntity;
import com.amalitech.labresultsvalidator.domain.lab.entity.Lab;
import com.amalitech.labresultsvalidator.domain.specialization.entity.Specialization;
import com.amalitech.labresultsvalidator.domain.user_module_assignment.entity.UserModuleAssignment;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "modules",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_module_name",
            columnNames = {"specialization_id", "name"}),
        @UniqueConstraint(
            name = "uq_module_sequence",
            columnNames = {"specialization_id", "sequence"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Module extends BaseEntity {

    /** Maximum character length for the module name. */
    private static final int NAME_MAX_LENGTH = 150;

    /** Unique identifier for this module. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The specialization this module belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "specialization_id", nullable = false)
    private Specialization specialization;

    /** Display name of this module. */
    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    /** Ordering position of this module within its specialization. */
    @Column(name = "sequence", nullable = false)
    private int sequence;

    /** Labs contained within this module. */
    @OneToMany(mappedBy = "module",
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    private List<Lab> labs;

    /** Instructor assignments for this module. */
    @OneToMany(mappedBy = "module",
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    private List<UserModuleAssignment> userModuleAssignments;
}
