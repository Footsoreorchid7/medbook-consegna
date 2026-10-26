package it.medbook.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import it.medbook.model.StatoRimborso;

public class RimborsoResponse {
    private Integer idRimborso;
    private Integer idPrenotazione;
    private Integer idPagamento;
    private StatoRimborso stato;
    private BigDecimal importo;
    private String motivazione;
    private LocalDateTime richiestoIl;
    private LocalDateTime verificatoIl;
    public Integer getIdRimborso() { return idRimborso; }
    public void setIdRimborso(Integer idRimborso) { this.idRimborso = idRimborso; }
    public Integer getIdPrenotazione() { return idPrenotazione; }
    public void setIdPrenotazione(Integer idPrenotazione) { this.idPrenotazione = idPrenotazione; }
    public Integer getIdPagamento() { return idPagamento; }
    public void setIdPagamento(Integer idPagamento) { this.idPagamento = idPagamento; }
    public StatoRimborso getStato() { return stato; }
    public void setStato(StatoRimborso stato) { this.stato = stato; }
    public BigDecimal getImporto() { return importo; }
    public void setImporto(BigDecimal importo) { this.importo = importo; }
    public String getMotivazione() { return motivazione; }
    public void setMotivazione(String motivazione) { this.motivazione = motivazione; }
    public LocalDateTime getRichiestoIl() { return richiestoIl; }
    public void setRichiestoIl(LocalDateTime richiestoIl) { this.richiestoIl = richiestoIl; }
    public LocalDateTime getVerificatoIl() { return verificatoIl; }
    public void setVerificatoIl(LocalDateTime verificatoIl) { this.verificatoIl = verificatoIl; }
}
