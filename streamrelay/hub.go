package main

import (
	"bytes"
	"fmt"
	"io"
)

type frame struct {
	packet     []byte
	packetSize int
}

type hub struct {
	streamer        *streamer
	players         map[string]*player
	frames          chan frame
	registrations   chan *player
	deregistrations chan *player
}

func (h *hub) run() {
	for {
		select {
		case player := <-h.registrations:
			h.register(player)
		case player := <-h.deregistrations:
			h.deregister(player)
		case frame := <-h.frames:
			h.broadcastFrame(frame)
		}
	}
}

func (h *hub) broadcastFrame(frame frame) {
	// TODO add timer to send frame at FPS speed?
	// if 30FPS, send 1 frame every 33 milliseconds?
	// only stream packet
	reader := bytes.NewReader(frame.packet)

	for _, player := range h.players {
		fmt.Println("Write frame to player ", player.name)
		io.CopyN(player.conn, reader, int64(frame.packetSize))
	}
}

func (h *hub) register(player *player) {
	if _, exists := h.players[player.name]; exists {
		// already register, lets ignore
	} else {
		h.players[player.name] = player
	}
}

func (h *hub) deregister(player *player) {
	if _, exists := h.players[player.name]; exists {
		delete(h.players, player.name)
	}
}

func createHub() *hub {
	return &hub{
		registrations:   make(chan *player),
		deregistrations: make(chan *player),
		frames:          make(chan frame),
		players:         make(map[string]*player),
	}
}
