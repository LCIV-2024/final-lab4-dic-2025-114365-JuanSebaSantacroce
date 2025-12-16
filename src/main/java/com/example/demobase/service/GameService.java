package com.example.demobase.service;

import com.example.demobase.dto.GameDTO;
import com.example.demobase.dto.GameResponseDTO;
import com.example.demobase.model.Game;
import com.example.demobase.model.GameInProgress;
import com.example.demobase.model.Player;
import com.example.demobase.model.Word;
import com.example.demobase.repository.GameInProgressRepository;
import com.example.demobase.repository.GameRepository;
import com.example.demobase.repository.PlayerRepository;
import com.example.demobase.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GameService {
    
    private final GameRepository gameRepository;
    private final GameInProgressRepository gameInProgressRepository;
    private final PlayerRepository playerRepository;
    private final WordRepository wordRepository;
    
    private static final int MAX_INTENTOS = 7;
    private static final int PUNTOS_PALABRA_COMPLETA = 20;
    private static final int PUNTOS_POR_LETRA = 1;
    
    @Transactional
    public GameResponseDTO startGame(Long playerId) {
        // Validar que el jugador existe
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Jugador no encontrado con ID: " + playerId));

        // Verificar si ya existe una partida en curso para este jugador
        if (!gameInProgressRepository.findByJugadorIdOrderByFechaInicioDesc(playerId).isEmpty()) {
            throw new IllegalStateException("El jugador ya tiene una partida en curso.");
        }

        // Encontrar una palabra aleatoria no utilizada
        Word word = wordRepository.findRandomWord()
                .orElseThrow(() -> new IllegalStateException("No hay palabras disponibles para jugar."));

        // Marcar la palabra como utilizada
        word.setUtilizada(true);
        wordRepository.save(word);

        // Crear nueva partida en curso
        GameInProgress newGame = new GameInProgress();
        newGame.setJugador(player);
        newGame.setPalabra(word);
        newGame.setLetrasIntentadas("");
        newGame.setIntentosRestantes(MAX_INTENTOS);
        newGame.setFechaInicio(LocalDateTime.now());
        gameInProgressRepository.save(newGame);

        return buildResponseFromGameInProgress(newGame);
    }
    
    @Transactional
    public GameResponseDTO makeGuess(Long playerId, Character letra) {
        // Buscar la partida en curso más reciente del jugador
        GameInProgress gameInProgress = gameInProgressRepository.findByJugadorIdOrderByFechaInicioDesc(playerId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay partida en curso para el jugador con ID: " + playerId));

        // Convertir la letra a mayúscula
        letra = Character.toUpperCase(letra);

        // Obtener letras ya intentadas
        Set<Character> letrasIntentadas = stringToCharSet(gameInProgress.getLetrasIntentadas());

        // Verificar si la letra ya fue intentada
        if (letrasIntentadas.contains(letra)) {
            // Si ya se intentó, no hacer nada y devolver el estado actual
            return buildResponseFromGameInProgress(gameInProgress);
        }

        // Agregar la nueva letra
        letrasIntentadas.add(letra);
        gameInProgress.setLetrasIntentadas(charSetToString(letrasIntentadas));

        // Verificar si la letra está en la palabra y decrementar intentos si es incorrecta
        String palabraSecreta = gameInProgress.getPalabra().getPalabra().toUpperCase();
        if (!palabraSecreta.contains(String.valueOf(letra))) {
            gameInProgress.setIntentosRestantes(gameInProgress.getIntentosRestantes() - 1);
        }

        // Verificar condiciones de fin de juego
        String palabraOcultaActual = generateHiddenWord(palabraSecreta, letrasIntentadas);
        boolean juegoGanado = palabraOcultaActual.equals(palabraSecreta);
        boolean juegoPerdido = gameInProgress.getIntentosRestantes() <= 0;

        // Si el juego terminó
        if (juegoGanado || juegoPerdido) {
            int puntaje = calculateScore(palabraSecreta, letrasIntentadas, juegoGanado, gameInProgress.getIntentosRestantes());
            saveGame(gameInProgress.getJugador(), gameInProgress.getPalabra(), juegoGanado, puntaje);
            gameInProgressRepository.delete(gameInProgress);

            // Construir respuesta final
            GameResponseDTO finalResponse = new GameResponseDTO();
            finalResponse.setPalabraOculta(palabraSecreta); // Revelar la palabra al final
            finalResponse.setLetrasIntentadas(new ArrayList<>(letrasIntentadas));
            finalResponse.setIntentosRestantes(gameInProgress.getIntentosRestantes());
            finalResponse.setPalabraCompleta(juegoGanado);
            finalResponse.setPuntajeAcumulado(puntaje);
            return finalResponse;

        } else {
            // Si el juego no terminó, guardar el estado actualizado
            gameInProgressRepository.save(gameInProgress);
            return buildResponseFromGameInProgress(gameInProgress);
        }
    }
    
    private GameResponseDTO buildResponseFromGameInProgress(GameInProgress gameInProgress) {
        String palabra = gameInProgress.getPalabra().getPalabra().toUpperCase();
        Set<Character> letrasIntentadas = stringToCharSet(gameInProgress.getLetrasIntentadas());
        String palabraOculta = generateHiddenWord(palabra, letrasIntentadas);
        boolean palabraCompleta = palabraOculta.equals(palabra);
        
        GameResponseDTO response = new GameResponseDTO();
        response.setPalabraOculta(palabraOculta);
        response.setLetrasIntentadas(new ArrayList<>(letrasIntentadas));
        response.setIntentosRestantes(gameInProgress.getIntentosRestantes());
        response.setPalabraCompleta(palabraCompleta);
        
        int puntaje = calculateScore(palabra, letrasIntentadas, palabraCompleta, gameInProgress.getIntentosRestantes());
        response.setPuntajeAcumulado(puntaje);
        
        return response;
    }
    
    private Set<Character> stringToCharSet(String str) {
        Set<Character> set = new HashSet<>();
        if (str != null && !str.isEmpty()) {
            String[] chars = str.split(",");
            for (String c : chars) {
                if (!c.trim().isEmpty()) {
                    set.add(c.trim().charAt(0));
                }
            }
        }
        return set;
    }
    
    private String charSetToString(Set<Character> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        return set.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
    
    private int calculateScore(String palabra, Set<Character> letrasIntentadas, boolean palabraCompleta, int intentosRestantes) {
        if (palabraCompleta) {
            return PUNTOS_PALABRA_COMPLETA;
        } else if (intentosRestantes == 0) {
            // Contar letras correctas encontradas
            long letrasCorrectas = letrasIntentadas.stream()
                    .filter(letra -> palabra.indexOf(letra) >= 0)
                    .count();
            return (int) (letrasCorrectas * PUNTOS_POR_LETRA);
        }
        return 0;
    }
    
    private String generateHiddenWord(String palabra, Set<Character> letrasIntentadas) {
        StringBuilder hidden = new StringBuilder();
        for (char c : palabra.toCharArray()) {
            if (letrasIntentadas.contains(c) || c == ' ') {
                hidden.append(c);
            } else {
                hidden.append('_');
            }
        }
        return hidden.toString();
    }
    
    @Transactional
    private void saveGame(Player player, Word word, boolean ganado, int puntaje) {
        // Asegurar que la palabra esté marcada como utilizada
        if (!word.getUtilizada()) {
            word.setUtilizada(true);
            wordRepository.save(word);
        }
        
        Game game = new Game();
        game.setJugador(player);
        game.setPalabra(word);
        game.setResultado(ganado ? "GANADO" : "PERDIDO");
        game.setPuntaje(puntaje);
        game.setFechaPartida(LocalDateTime.now());
        gameRepository.save(game);
    }
    
    public List<GameDTO> getGamesByPlayer(Long playerId) {
        return gameRepository.findByJugadorId(playerId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    public List<GameDTO> getAllGames() {
        return gameRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    private GameDTO toDTO(Game game) {
        GameDTO dto = new GameDTO();
        dto.setId(game.getId());
        dto.setIdJugador(game.getJugador().getId());
        dto.setNombreJugador(game.getJugador().getNombre());
        dto.setResultado(game.getResultado());
        dto.setPuntaje(game.getPuntaje());
        dto.setFechaPartida(game.getFechaPartida());
        dto.setPalabra(game.getPalabra() != null ? game.getPalabra().getPalabra() : null);
        return dto;
    }
}

