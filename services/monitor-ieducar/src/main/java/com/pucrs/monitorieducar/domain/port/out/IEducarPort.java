package com.pucrs.monitorieducar.domain.port.out;

import com.pucrs.monitorieducar.domain.model.Matricula;
import com.pucrs.monitorieducar.domain.model.Turma;
import com.pucrs.monitorieducar.domain.model.VinculoProfessor;

import java.util.Collection;
import java.util.List;

/** Fonte dos dados (iEducar). Pode lançar {@code SistemaExternoException}. */
public interface IEducarPort {

    List<Turma> listarTurmas();

    List<Matricula> listarMatriculas(int ano);

    List<VinculoProfessor> listarVinculosProfessores(int ano, Collection<Long> escolaIds);
}
