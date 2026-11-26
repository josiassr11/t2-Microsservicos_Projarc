package com.pucrs.publicadormoodle.adapter.in.messaging;

import com.pucrs.publicadormoodle.application.ComandoRecebido;
import com.pucrs.publicadormoodle.application.ProcessadorComando;
import com.pucrs.publicadormoodle.config.Topologia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Consumidor da fila de comandos. O ack é automático quando o método retorna (exceção => volta para a fila). */
@Component
public class ComandoListener {

    private static final Logger log = LoggerFactory.getLogger(ComandoListener.class);

    private final ProcessadorComando processador;

    public ComandoListener(ProcessadorComando processador) {
        this.processador = processador;
    }

    @RabbitListener(queues = Topologia.FILA_COMANDOS)
    public void receber(Message mensagem) {
        var props = mensagem.getMessageProperties();
        int tentativa = tentativa(props.getHeader(Topologia.HEADER_TENTATIVA));
        String corr = props.getCorrelationId();
        try {
            ComandoRecebido comando;
            try {
                comando = ComandoParser.parse(mensagem.getBody(), props.getMessageId(), corr);
            } catch (ComandoIlegivelException e) {
                if (corr != null) {
                    MDC.put("correlationId", corr);
                }
                log.warn("[RECEBE] fila={} tipo={} messageId={} -> ilegível: {}", Topologia.FILA_COMANDOS, props.getType(),
                        props.getMessageId(), e.getMessage());
                processador.rejeitarIlegivel(mensagem.getBody(), props.getMessageId(), corr, tentativa, e.getMessage());
                return;
            }
            MDC.put("correlationId", comando.correlationId());
            log.info("[RECEBE] fila={} tipo={} eventId={} turmaId={} versão={} x-tentativa={}", Topologia.FILA_COMANDOS,
                    props.getType(), comando.commandId(), comando.turmaId(), comando.comando().versao(), tentativa);
            processador.processar(comando, tentativa);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private static int tentativa(Object header) {
        return header instanceof Number n ? Math.max(1, n.intValue()) : 1;
    }
}
