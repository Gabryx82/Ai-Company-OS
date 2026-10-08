"""A completion stops when its caller goes away (PHASE 32, TD-39).

The control plane cancels a run by interrupting the thread that waits for this
engine, which closes the HTTP connection. Without this, the provider call would
go on to the end -- minutes of a local model working for nobody. While the work
runs, the caller is polled; once it is gone the work is cancelled, which cancels
the provider's own request (for Ollama, closing it stops the generation).
"""
import asyncio
import contextlib
from collections.abc import Awaitable, Callable
from typing import TypeVar

T = TypeVar("T")


class CallerGone(Exception):
    """The caller disconnected before the work finished; the work was cancelled."""


async def until_caller_leaves(work: Awaitable[T], is_disconnected: Callable[[], Awaitable[bool]],
                              poll_seconds: float = 0.5) -> T:
    """Awaits ``work``; cancels it and raises :class:`CallerGone` as soon as the caller is gone."""
    task = asyncio.ensure_future(work)
    try:
        while True:
            done, _ = await asyncio.wait({task}, timeout=poll_seconds)
            if done:
                return task.result()
            if await is_disconnected():
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task
                raise CallerGone()
    finally:
        if not task.done():
            task.cancel()
