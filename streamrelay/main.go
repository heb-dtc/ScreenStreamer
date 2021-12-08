package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
)

type streamer struct {
	conn      net.Conn
	framePipe chan<- frame
}

type player struct {
	conn       net.Conn
	name       string
	register   chan<- *player
	deregister chan<- *player
}

func createPlayer(c net.Conn, r chan<- *player, d chan<- *player) *player {
	return &player{
		conn:       c,
		name:       c.RemoteAddr().String(),
		register:   r,
		deregister: d,
	}
}

func main() {
	fmt.Println("================  StreamRelay =================")
	fmt.Println("|       streamer: tcp://localhost:540000      |")
	fmt.Println("|       players: tcp:// localhost:540001      |")
	fmt.Println("===============================================")
	fmt.Println("")

	// create the hub and start it
	hub := createHub()
	go hub.run()

	// create the streamer endpoint
	streamerSocket, err := net.Listen("tcp", "localhost:54000")
	if err != nil {
		fmt.Println("error starting stream relay:", err.Error())
		os.Exit(1)
	}
	defer streamerSocket.Close()

	// wait for streamer
	go waitForStreamer(streamerSocket, hub.frames)

	// create the players endpoint
	playersSocket, err := net.Listen("tcp", "localhost:54001")
	if err != nil {
		fmt.Println("error starting stream relay:", err.Error())
		os.Exit(1)
	}
	defer playersSocket.Close()

	// wait for players
	for {
		fmt.Println("")
		fmt.Println("Waiting for players to connect")
		conn, err := playersSocket.Accept()
		if err != nil {
			fmt.Println("New player failed to connect:", err.Error())
			return
		}
		fmt.Println("----> New player has connected " + conn.RemoteAddr().String())
		p := createPlayer(
			conn,
			hub.registrations,
			hub.deregistrations,
		)

		hub.registrations <- p
	}
}

func waitForStreamer(server net.Listener, framePipe chan<- frame) {
	fmt.Println("")
	fmt.Println("Waiting for streamer to connect")
	sConn, err := server.Accept()
	if err != nil {
		fmt.Println("streamer failed to connect:", err.Error())
		return
	}
	fmt.Println("----> Streamer has connected " + sConn.RemoteAddr().String())
	s := &streamer{
		conn:      sConn,
		framePipe: framePipe,
	}

	go s.stream()
}

func (s *streamer) stream() {
	for {
		//log.Println("")
		//log.Println("Read incoming frame")

		// read first 12 bytes (pts/8bytes + packetSize/4bytes)
		buf := make([]byte, 12)
		io.ReadFull(s.conn, buf)

		ptsBuf := buf[:8]
		sizeBuf := buf[8:]

		var pts uint64
		binary.Read(bytes.NewReader(ptsBuf), binary.BigEndian, &pts)
		var size uint32
		binary.Read(bytes.NewReader(sizeBuf), binary.BigEndian, &size)

		//log.Println("Frame metadata: pts is -> ", pts, " and packet size is ", size)
		//log.Println("")

		packet := make([]byte, size)
		io.ReadFull(s.conn, packet)

		// emit frame
		frame := frame{
			packet:     packet,
			packetSize: int(size),
		}
		s.framePipe <- frame
	}
}
