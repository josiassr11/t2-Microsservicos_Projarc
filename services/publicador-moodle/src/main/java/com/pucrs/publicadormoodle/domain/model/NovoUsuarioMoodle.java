package com.pucrs.publicadormoodle.domain.model;

public record NovoUsuarioMoodle(String username, String senha, String primeiroNome, String sobrenome,
                                String email, String idNumber) {
}
