package it.medbook.dto;

import it.medbook.model.ModalitaPagamento;
import it.medbook.model.StatoPagamento;
import it.medbook.model.StatoPrenotazione;
import it.medbook.model.StatoRimborso;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Voce di GET /api/prenotazioni/desk . Gli operatori compaiono solo come ID utente.
 * gestitaDa/dataOraGestione indicano l'ultima gestione desk registrata sulla riga, non l'autore
 * dello stato corrente: l'annullamento del paziente, per esempio, non li modifica.
 * Nessun recapito, codice fiscale, motivazione o identificativo del provider.
 */
public class PrenotazioneDeskResponse {
    private Integer idPrenotazione;
    private StatoPrenotazione stato;
    private boolean indisponibilitaSegnalata;
    private BigDecimal prezzo;
    private Integer idDisponibilita;
    private LocalDateTime dataOraInizio;
    private LocalDateTime dataOraFine;
    private Integer idPaziente;
    private String nomePaziente;
    private String cognomePaziente;
    private Integer idMedicoTitolare;
    private String nomeMedicoTitolare;
    private String cognomeMedicoTitolare;
    private Integer idMedicoEffettivo;
    private String nomeMedicoEffettivo;
    private String cognomeMedicoEffettivo;
    private Integer idPrestazione;
    private String nomePrestazione;
    private Integer idStanza;
    private String nomeStanza;
    private Integer gestitaDa;
    private LocalDateTime dataOraGestione;
    private final List<Pagamento> pagamenti = new ArrayList<>();
    private final List<Rimborso> rimborsi = new ArrayList<>();

    /** Tentativo di pagamento: registratoDa è valorizzato solo per DESK_POS/DESK_CONTANTI. */
    public record Pagamento(Integer idPagamento, ModalitaPagamento modalita, BigDecimal importo, StatoPagamento stato,
                            LocalDateTime creatoIl, LocalDateTime pagatoIl, Integer registratoDa) { }

    /** Rimborso di un pagamento della stessa prenotazione; verificatoDa/verificatoIl insieme o nulli. */
    public record Rimborso(Integer idRimborso, Integer idPagamento, StatoRimborso stato, BigDecimal importo,
                           LocalDateTime richiestoIl, LocalDateTime verificatoIl, Integer verificatoDa) { }

    public Integer getIdPrenotazione() { return idPrenotazione; }
    public void setIdPrenotazione(Integer idPrenotazione) { this.idPrenotazione = idPrenotazione; }

    public StatoPrenotazione getStato() { return stato; }
    public void setStato(StatoPrenotazione stato) { this.stato = stato; }

    public boolean isIndisponibilitaSegnalata() { return indisponibilitaSegnalata; }
    public void setIndisponibilitaSegnalata(boolean value) { indisponibilitaSegnalata = value; }

    public BigDecimal getPrezzo() { return prezzo; }
    public void setPrezzo(BigDecimal prezzo) { this.prezzo = prezzo; }

    public Integer getIdDisponibilita() { return idDisponibilita; }
    public void setIdDisponibilita(Integer idDisponibilita) { this.idDisponibilita = idDisponibilita; }

    public LocalDateTime getDataOraInizio() { return dataOraInizio; }
    public void setDataOraInizio(LocalDateTime dataOraInizio) { this.dataOraInizio = dataOraInizio; }

    public LocalDateTime getDataOraFine() { return dataOraFine; }
    public void setDataOraFine(LocalDateTime dataOraFine) { this.dataOraFine = dataOraFine; }

    public Integer getIdPaziente() { return idPaziente; }
    public void setIdPaziente(Integer idPaziente) { this.idPaziente = idPaziente; }

    public String getNomePaziente() { return nomePaziente; }
    public void setNomePaziente(String nomePaziente) { this.nomePaziente = nomePaziente; }

    public String getCognomePaziente() { return cognomePaziente; }
    public void setCognomePaziente(String cognomePaziente) { this.cognomePaziente = cognomePaziente; }

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

    public Integer getIdPrestazione() { return idPrestazione; }
    public void setIdPrestazione(Integer idPrestazione) { this.idPrestazione = idPrestazione; }

    public String getNomePrestazione() { return nomePrestazione; }
    public void setNomePrestazione(String nomePrestazione) { this.nomePrestazione = nomePrestazione; }

    public Integer getIdStanza() { return idStanza; }
    public void setIdStanza(Integer idStanza) { this.idStanza = idStanza; }

    public String getNomeStanza() { return nomeStanza; }
    public void setNomeStanza(String nomeStanza) { this.nomeStanza = nomeStanza; }

    public Integer getGestitaDa() { return gestitaDa; }
    public void setGestitaDa(Integer gestitaDa) { this.gestitaDa = gestitaDa; }

    public LocalDateTime getDataOraGestione() { return dataOraGestione; }
    public void setDataOraGestione(LocalDateTime dataOraGestione) { this.dataOraGestione = dataOraGestione; }

    public List<Pagamento> getPagamenti() { return pagamenti; }

    public List<Rimborso> getRimborsi() { return rimborsi; }
}
