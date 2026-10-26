package it.medbook.service;

import it.medbook.dto.RegistrazionePazienteRequest;
import it.medbook.repository.RegistrazioneRepository;
import it.medbook.security.PasswordHasher;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

public class RegistrazioneService {
    private final RegistrazioneRepository repository;
    private final PasswordHasher hasher;
    private final Clock clock;
    public RegistrazioneService(RegistrazioneRepository repository,PasswordHasher hasher){this(repository,hasher,Clock.systemDefaultZone());}
    public RegistrazioneService(RegistrazioneRepository repository,PasswordHasher hasher,Clock clock){
        this.repository=Objects.requireNonNull(repository);this.hasher=Objects.requireNonNull(hasher);this.clock=Objects.requireNonNull(clock);
    }
    public void registra(RegistrazionePazienteRequest request) throws SQLException {
        if(request==null){throw invalid();}
        String nome=testo(request.getNome(),100),cognome=testo(request.getCognome(),100);
        String email=testo(request.getEmail(),255).toLowerCase(Locale.ROOT);
        if(!email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")){
            throw new IllegalArgumentException("Email non valida.");
        }
        String local=email.substring(0,email.indexOf('@'));
        if(local.length()>64 || local.startsWith(".") || local.endsWith(".") || local.contains("..")){
            throw new IllegalArgumentException("Email non valida.");
        }
        char[] password=request.getPassword();
        ValidazioneAccount.passwordPaziente(password);
        var profilo=ValidazioneProfiloPaziente.valida(request.getCodiceFiscale(),request.getDataNascita(),request.getTelefono(),clock);
        String hash=hasher.hash(password);
        repository.registraPaziente(nome,cognome,email,hash,profilo.codiceFiscale(),profilo.dataNascita(),profilo.telefono());
    }
    private String testo(String value,int max){
        if(value==null){throw invalid();}String result=value.strip();
        if(result.isEmpty() || result.codePointCount(0,result.length())>max || result.codePoints().anyMatch(Character::isISOControl)){throw invalid();}
        return result;
    }
    private IllegalArgumentException invalid(){return new IllegalArgumentException("Dati di registrazione non validi.");}
}
