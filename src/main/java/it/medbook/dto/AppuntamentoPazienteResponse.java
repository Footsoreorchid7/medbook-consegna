package it.medbook.dto;

import it.medbook.model.StatoPrenotazione;
import java.time.LocalDateTime;
import java.math.BigDecimal;

public class AppuntamentoPazienteResponse {
    private Integer idPrenotazione;
    private StatoPrenotazione stato;
    private LocalDateTime dataOraRichiesta;
    private BigDecimal prezzo;
    private Integer idDisponibilita;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private Integer idPrestazione;
    private String nomePrestazione;
    private Integer durataPrestazione;
    private Integer idMedicoTitolare;
    private String nomeMedicoTitolare;
    private String cognomeMedicoTitolare;
    private Integer idMedicoEffettivo;
    private String nomeMedicoEffettivo;
    private String cognomeMedicoEffettivo;
    private Integer idStanza;
    private String nomeStanza;

    public Integer getIdPrenotazione() { return idPrenotazione; }
    public void setIdPrenotazione(Integer idPrenotazione) { this.idPrenotazione = idPrenotazione; }

    public StatoPrenotazione getStato() { return stato; }
    public void setStato(StatoPrenotazione stato) { this.stato = stato; }

    public LocalDateTime getDataOraRichiesta() { return dataOraRichiesta; }
    public void setDataOraRichiesta(LocalDateTime dataOraRichiesta) { this.dataOraRichiesta = dataOraRichiesta; }

    public BigDecimal getPrezzo() { return prezzo; }
    public void setPrezzo(BigDecimal prezzo) { this.prezzo = prezzo; }

    public Integer getIdDisponibilita() { return idDisponibilita; }
    public void setIdDisponibilita(Integer idDisponibilita) { this.idDisponibilita = idDisponibilita; }

    public LocalDateTime getDataOraInizio() { return dataOraInizio; }
    public void setDataOraInizio(LocalDateTime dataOraInizio) { this.dataOraInizio = dataOraInizio; }

    public LocalDateTime getDataOraFine() { return dataOraFine; }
    public void setDataOraFine(LocalDateTime dataOraFine) { this.dataOraFine = dataOraFine; }

    public Integer getIdPrestazione() { return idPrestazione; }
    public void setIdPrestazione(Integer idPrestazione) { this.idPrestazione = idPrestazione; }

    public String getNomePrestazione() { return nomePrestazione; }
    public void setNomePrestazione(String nomePrestazione) { this.nomePrestazione = nomePrestazione; }

    public Integer getDurataPrestazione() { return durataPrestazione; }
    public void setDurataPrestazione(Integer durataPrestazione) { this.durataPrestazione = durataPrestazione; }

    public Integer getIdMedicoTitolare() { return idMedicoTitolare; }
    public void setIdMedicoTitolare(Integer idMedicoTitolare) { this.idMedicoTitolare = idMedicoTitolare; }

    public String getNomeMedicoTitolare() { return nomeMedicoTitolare; }
    public void setNomeMedicoTitolare(String nomeMedicoTitolare) { this.nomeMedicoTitolare = nomeMedicoTitolare; }

    public String getCognomeMedicoTitolare() { return cognomeMedicoTitolare; }
    public void setCognomeMedicoTitolare(String cognomeMedicoTitolare) { this.cognomeMedicoTitolare = cognomeMedicoTitolare; }

    public Integer getIdMedicoEffettivo() { return idMedicoEffettivo; }
    public void setIdMedicoEffettivo(Integer idMedicoEffettivo) { this.idMedicoEffettivo = idMedicoEffettivo; }

    public String getNomeMedicoEffettivo() { return nomeMedicoEffettivo; }
    public void setNomeMedicoEffettivo(String nomeMedicoEffettivo) { this.nomeMedicoEffettivo = nomeMedicoEffettivo; }

    public String getCognomeMedicoEffettivo() { return cognomeMedicoEffettivo; }
    public void setCognomeMedicoEffettivo(String cognomeMedicoEffettivo) { this.cognomeMedicoEffettivo = cognomeMedicoEffettivo; }

    public Integer getIdStanza() { return idStanza; }
    public void setIdStanza(Integer idStanza) { this.idStanza = idStanza; }

    public String getNomeStanza() { return nomeStanza; }
    public void setNomeStanza(String nomeStanza) { this.nomeStanza = nomeStanza; }
}
