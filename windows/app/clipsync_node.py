"""
ClipSync - this PC's identity on the network: an id, a type, and what it can promise about staying
connected. See docs/p2p-plan.md §1 and §2.

**The id is generated once per process and never stored.** It identifies a *session*, not an
installation, and every use of it is within one session: link dedup, the self-connection guard, the
recipient list in an OFFER, the priority tie-break. All of those compare ids that arrived in a HELLO
on a connection that is currently open. Nothing compares an id against one from last week.

So the file that used to hold it bought one thing — a restart being recognised as the same node —
and charged for it in atomic writes, a half-written-id failure mode, an installer that had to know
not to touch it, and, worst, a value people could hand-edit into a collision. Two nodes sharing one
id breaks dedup and relay selection in ways that are very hard to trace back to their cause, and a
file is an invitation to exactly that. A fresh UUID per start cannot collide at all.

What that costs is written down in §1: after an *unclean* restart, a peer that has not yet noticed
the old TCP connection is dead sees the returning node as a new one, so for up to one read timeout
it holds a live link and a stale one and cannot collapse them by id. That is bounded, self-healing,
and costs duplicate delivery rather than lost delivery.
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
    The fields a node puts in HELLO beside `v` (§2).

    `persistent` is a declaration of capability, not an inference from type — and a PC on mains
    power that is running this service can always hold a connection while idle, so it is simply
    true here. It is the Android side where the distinction earns its keep.

    `data_out` is the other capability, and it is why this is a declaration rather than something
    either end infers.  A file's bytes move over separate data connections, and exactly one of the
    two nodes must open them — both opening means the file is transferred twice, neither means it
    is not transferred at all.  This PC can only *accept* them, so it says so, and the peer takes
    the job.  When both ends can, the rule is "the node that dialled opens them"; it is only
    because this end cannot that a rule is needed at all.

    `port` is where this node listens, which an accepted connection cannot work out for itself:
    the source port it sees is ephemeral and reaches nothing.
    """
    return {
        "id": NODE_ID,
        # `device` and not a new `name`: the friendly name already had a field, and two fields
        # meaning one thing is how they end up disagreeing.
        "device": node_name(),
        "type": "pc",
        "persistent": True,
        "battery": "mains",
        "data_out": False,
        **({"port": cfg.port} if cfg is not None else {}),
    }
