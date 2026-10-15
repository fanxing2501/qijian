#!/bin/sh
set -eu
mkdir -p .runtime/player-fixture/media/hls .runtime/player-fixture/media/dash
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=320x180:rate=15' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 12 -c:v libvpx -threads 2 -b:v 150k -c:a libopus .runtime/player-fixture/media/sample.webm
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=320x180:rate=15' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 6 -c:v libx264 -threads 2 -preset ultrafast -g 15 -pix_fmt yuv420p -c:a aac -f hls -hls_time 1 -hls_playlist_type vod .runtime/player-fixture/media/hls/stream.m3u8
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=320x180:rate=15' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 6 -c:v libx264 -threads 2 -preset ultrafast -g 15 -pix_fmt yuv420p -c:a aac -f dash -seg_duration 1 .runtime/player-fixture/media/dash/stream.mpd
