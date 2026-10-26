package it.medbook.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import it.medbook.model.StatoRimborso;

public class RichiestaRimborsoDeskResponse {
    private Integer idOperatore;
    private String nomeOperatore;
    private String cognomeOperatore;
    public Integer getIdOperatore() { return idOperatore; }
    public void setIdOperatore(Integer value) { idOperatore = value; }
    public String getNomeOperatore() { return nomeOperatore; }
    public void setNomeOperatore(String value) { nomeOperatore = value; }
    public String getCognomeOperatore() { return cognomeOperatore; }
    public void setCognomeOperatore(String value) { cognomeOperatore = value; }
    private Integer idRimborso;
    private Integer idPrenotazione;
    private Integer idPagamento;
    private StatoRimborso stato;
    private BigDecimal importo;
    private String motivazione;
    private LocalDateTime richiestoIl;
    private LocalDateTime verificatoIl;
    private Integer idPaziente;
    private String nomePaziente;
    private String cognomePaziente;
    private Integer idPrestazione;
    private String nomePrestazione;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private String modalitaPagamento;
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
    public Integer getIdPaziente() { return idPaziente; }
    public void setIdPaziente(Integer idPaziente) { this.idPaziente = idPaziente; }
    public String getNomePaziente() { return nomePaziente; }
    public void setNomePaziente(String nomePaziente) { this.nomePaziente = nomePaziente; }
    public String getCognomePaziente() { return cognomePaziente; }
    public void setCognomePaziente(String cognomePaziente) { this.cognomePaziente = cognomePaziente; }
    public Integer getIdPrestazione() { return idPrestazione; }
    public void setIdPrestazione(Integer idPrestazione) { this.idPrestazione = idPrestazione; }
    public String getNomePrestazione() { return nomePrestazione; }
    public void setNomePrestazione(String nomePrestazione) { this.nomePrestazione = nomePrestazione; }
    public LocalDateTime getDataOraInizio() { return dataOraInizio; }
    public void setDataOraInizio(LocalDateTime dataOraInizio) { this.dataOraInizio = dataOraInizio; }
    public LocalDateTime getDataOraFine() { return dataOraFine; }
    public void setDataOraFine(LocalDateTime dataOraFine) { this.dataOraFine = dataOraFine; }
    public String getModalitaPagamento() { return modalitaPagamento; }
    public void setModalitaPagamento(String modalitaPagamento) { this.modalitaPagamento = modalitaPagamento; }
}
