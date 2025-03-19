package com.pm.patientservice.mapper;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.model.Patient;

import java.time.LocalDate;

public class PatientMapper {

    public static PatientResponseDTO toDTO(Patient patient) {
        PatientResponseDTO patientDTO = new PatientResponseDTO();
        patientDTO.setId(patient.getId().toString());
        patientDTO.setName(patient.getName());
        patientDTO.setEmail(patient.getEmail());
        patientDTO.setAddress(patient.getAddress());
        patientDTO.setDateOfBirth(patient.getDateOfBirth().toString());
        return patientDTO;
    }

    public static Patient toModel(PatientRequestDTO patientRequestDTODTO) {
        Patient patient = new Patient();
        patient.setName(patientRequestDTODTO.getName());
        patient.setEmail(patientRequestDTODTO.getEmail());
        patient.setAddress(patientRequestDTODTO.getAddress());
        patient.setDateOfBirth(LocalDate.parse(patientRequestDTODTO.getDateOfBirth()));
        patient.setRegisteredDate(LocalDate.parse(patientRequestDTODTO.getRegisteredDate()));

        return patient;
    }
}
