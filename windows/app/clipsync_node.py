"""
ClipSync - this PC's identity on the network: an id, a type, and what it can promise about staying
connected.

Every node has a random UUID and declares it in HELLO. Not a hash of the host name: host names are
neither unique (two `DESKTOP-XXXX` on one LAN is normal) nor stable (the user can rename at any
time), and hashing an unstable value only hides the instability. The id is the key for link dedup,
the recipient list in an OFFER, the priority tie-break and the self-connection guard, and it is
shown as its first 8 characters in logs and in the peer list beside the friendly name.

**The id is generated once per process and never stored.** It identifies a *session*, not an
installation, and every use of it is within one session: link dedup, the self-connection guard, the
recipient list in an OFFER, the priority tie-break. All of those compare ids that arrived in a HELLO
on a connection that is currently open. Nothing compares an id against one from last week.

So the file that used to hold it bought one thing — a restart being recognised as the same node —
and charged for it in atomic writes, a half-written-id failure mode, an installer that had to know
not to touch it, and, worst, a value people could hand-edit into a collision. Two nodes sharing one
id breaks dedup and relay selection in ways that are very hard to trace back to their cause, and a
file is an invitation to exactly that. A fresh UUID per start cannot collide at all.

The accepted cost is narrow and worth stating: after an *unclean* restart, a peer that has not yet
noticed the old TCP connection is dead sees the returning node as a new one, so for up to one read
timeout (90 s) it holds a live link and a stale one and cannot collapse them by id. That is bounded,
self-healing, needs a crash to happen at all — a clean restart closes its connections on the way out
— and costs duplicate delivery rather than lost delivery.

The one thing the file did provide and that had to be replaced deliberately is log continuity: each
side logs its own id in its first line, so every later `client <id> connected` has a session to
belong to.
"""
import socket
import uuid

# Generated at import, which is once per process. Not lazily: an id that appears at first use is an
# id whose value depends on when something first asked, and the log line below should be able to
# name it at startup.
NODE_ID = str(uuid.uuid4())


def node_id() -> str:
    return NODE_ID


def short_id(value: str) -> str:
    """The first 8 characters, which is what logs and the peer list show beside the friendly name."""
    return (value or "?")[:8]


def node_name() -> str:
    return socket.gethostname().split(".")[0]


def declaration(cfg=None) -> dict:
    """
    The fields a node puts in HELLO beside `v`.

    Six of them, and between them they are everything the other end cannot work out for itself:
    `id` (who this is), `device` (what to call it), `type` and `battery` and `persistent` (where it
    sits in the relay priority order), `port` and `data_out` (how to move a file's bytes to it).

    `persistent` is a declaration of capability, not an inference from type — and a PC on mains
    power that is running this service can always hold a connection while idle, so it would simply
    be true here. It is the Android side where the distinction earns its keep: a non-rooted phone on
    charge still gets frozen, and electing it as the LAN's relay elects a node that silently stops
    relaying.

    `relay_opt_out` is the one thing that makes it false on a PC, and this is the first of its two
    layers. The switch means "do not be the LAN's relay", and the relay is not elected by any
    message: every node derives it as the highest-priority member of the candidate set, and
    `persistent` is the first component of that order. Declaring false is therefore what takes this
    node out of everyone *else's* election, before anyone asks. The second layer — refusing a
    RELAY_ASK that reaches us anyway — is in SyncState.on_relay_ask, and both are needed: the
    declaration only reaches peers that handshake after the switch was set, while the refusal
    catches the rest.

    `data_out` is the other capability, and it is why this is a declaration rather than something
    either end infers.  A file's bytes move over separate data connections, and exactly one of the
    two nodes must open them — both opening means the file is transferred twice, neither means it
    is not transferred at all.  This PC now opens them as well as accepting them, so it says true,
    and the rule becomes the symmetric one: **the node that dialled opens them**, which both ends
    work out from the same two facts (see SecureChannel.drives_transfer).

    It said false for as long as there was no outbound implementation here, and the cost of that
    was not an inefficiency but a hang: between two PCs neither end would open anything, every WANT
    went unanswered, and the only trace was a log line saying we were waiting.

    `port` is where this node listens, which an accepted connection cannot work out for itself:
    the source port it sees is ephemeral and reaches nothing.
    """
    return {
        "id": NODE_ID,
        # `device` and not a new `name`: the friendly name already had a field, and two fields
        # meaning one thing is how they end up disagreeing.
        "device": node_name(),
        "type": "pc",
        "persistent": not (cfg is not None and cfg.relay_opt_out),
        "battery": "mains",
        "data_out": True,
        **({"port": cfg.port} if cfg is not None else {}),
    }
