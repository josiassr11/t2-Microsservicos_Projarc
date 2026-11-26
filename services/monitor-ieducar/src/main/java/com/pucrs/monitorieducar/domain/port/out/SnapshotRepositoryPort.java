package com.pucrs.monitorieducar.domain.port.out;

import com.pucrs.monitorieducar.domain.model.RegistroSnapshot;

import java.util.List;
import java.util.Optional;

public interface SnapshotRepositoryPort {

    Optional<String> buscarHash(long turmaId);

    /** Grava (ou atualiza) o snapshot da turma. Só deve ser chamado depois do confirm do broker. */
    void salvar(long turmaId, String hash, String nome, boolean ativa);

    List<RegistroSnapshot> listar();
}
