#!/usr/bin/env sh
# Aplica temporalmente latencia, pérdida o ancho de banda limitado al contenedor indicado usando tc/netem.
set -eu

if [ "$(uname -s)" != "Linux" ]; then
	echo "tc/netem solo está soportado en runners Linux con privilegios" >&2
	exit 2
fi

: "${NETEM_TARGET:=order-service}"
: "${NETEM_DELAY_MS:=250}"
: "${NETEM_JITTER_MS:=50}"
: "${NETEM_LOSS_PERCENT:=0}"
: "${NETEM_BANDWIDTH_KBIT:=0}"
: "${NETEM_DURATION_SECONDS:=30}"

container_id=$(docker compose ps -q "$NETEM_TARGET")
container_pid=$(docker inspect --format '{{.State.Pid}}' "$container_id")

cleanup() {
	nsenter -t "$container_pid" -n tc qdisc del dev eth0 root 2>/dev/null || true
}
trap cleanup EXIT INT TERM

arguments="delay ${NETEM_DELAY_MS}ms ${NETEM_JITTER_MS}ms"
if [ "$NETEM_LOSS_PERCENT" != "0" ]; then
	arguments="$arguments loss ${NETEM_LOSS_PERCENT}%"
fi
if [ "$NETEM_BANDWIDTH_KBIT" != "0" ]; then
	arguments="$arguments rate ${NETEM_BANDWIDTH_KBIT}kbit"
fi

nsenter -t "$container_pid" -n tc qdisc replace dev eth0 root netem $arguments
echo "netem target=$NETEM_TARGET delay=${NETEM_DELAY_MS}ms jitter=${NETEM_JITTER_MS}ms loss=${NETEM_LOSS_PERCENT}% bandwidth_kbit=${NETEM_BANDWIDTH_KBIT} duration_seconds=${NETEM_DURATION_SECONDS}"
sleep "$NETEM_DURATION_SECONDS"
