package com.chunkymonkey.chess.tournament.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chunkymonkey.chess.tournament.ChessTournamentApp
import com.chunkymonkey.chess.tournament.db.entity.Player
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val playerDao = ChessTournamentApp.getDatabase(application).playerDao()

    val allPlayers = playerDao.getAllPlayers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addPlayer(name: String, fideId: String, rating: Int) {
        viewModelScope.launch {
            playerDao.insert(Player(name = name, fideId = fideId, rating = rating))
        }
    }

    fun updatePlayer(player: Player) {
        viewModelScope.launch {
            playerDao.update(player)
        }
    }

    fun deletePlayer(player: Player) {
        viewModelScope.launch {
            playerDao.delete(player)
        }
    }
}
