package it.medbook.dto;

public class ConfiguraDisponibilitaRequest {
    private Integer idPrestazione;
    private Integer idStanza;

    public Integer getIdPrestazione() { return idPrestazione; }
    public void setIdPrestazione(Integer idPrestazione) { this.idPrestazione = idPrestazione; }
    public Integer getIdStanza() { return idStanza; }
    public void setIdStanza(Integer idStanza) { this.idStanza = idStanza; }
}
