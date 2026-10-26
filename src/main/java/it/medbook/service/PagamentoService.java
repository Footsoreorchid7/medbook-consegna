package it.medbook.service;

import it.medbook.dto.PagamentoResponse;
import it.medbook.dto.PagamentoPubblicoResponse;
import java.util.List;
import it.medbook.model.ModalitaPagamento;
import it.medbook.repository.PagamentoRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.Objects;

public class PagamentoService {
    private final PagamentoRepository repository;
    public PagamentoService(PagamentoRepository repository) { this.repository = Objects.requireNonNull(repository); }
    public PagamentoResponse registraPagamentoInSede(Integer idPrenotazione, Integer idOperatore,
            ModalitaPagamento modalita, BigDecimal importoAtteso) throws SQLException {
        valida(idPrenotazione, idOperatore, modalita, importoAtteso);
        return repository.registraInSedeAtomically(idPrenotazione, idOperatore, modalita, importoAtteso);
    }
    public static void valida(Integer prenotazione, Integer operatore, ModalitaPagamento modalita, BigDecimal importo) {
        if (prenotazione == null || prenotazione <= 0 || operatore == null || operatore <= 0) {
            throw new IllegalArgumentException("Gli ID devono essere positivi.");
        }
        if (modalita != ModalitaPagamento.DESK_POS && modalita != ModalitaPagamento.DESK_CONTANTI) {
            throw new IllegalArgumentException("Modalità di pagamento in sede non valida.");
        }
        if (importo == null || importo.signum() < 0 || importo.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new IllegalArgumentException("Importo non valido.");
        }
        try { importo.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Importo non rappresentabile con due decimali."); }
    }
    public PagamentoPubblicoResponse pagaOnline(Integer prenotazione, Integer paziente,
            BigDecimal importoAtteso) throws SQLException {
        PagamentoRepository.validaOnline(prenotazione, paziente, importoAtteso);
        return repository.registraOnlineAtomically(prenotazione, paziente, importoAtteso);
    }
    public List<PagamentoPubblicoResponse> getPagamenti(Integer prenotazione, Integer paziente)
            throws SQLException {
        if (prenotazione == null || prenotazione <= 0 || paziente == null || paziente <= 0) {
            throw new IllegalArgumentException("Gli ID devono essere positivi.");
        }
        return repository.findByPrenotazionePaziente(prenotazione, paziente);
    }

}
