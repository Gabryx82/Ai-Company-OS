"""PHASE 32 (TD-39): a completion stops when the control plane cancels the run and goes away."""
import asyncio

import pytest

from app.cancellation import CallerGone, until_caller_leaves


def test_the_work_is_cancelled_as_soon_as_the_caller_is_gone():
    state = {"cancelled": False, "polls": 0}

    async def provider_call():
        try:
            await asyncio.sleep(30)  # a local model generating for minutes
            return "nobody reads this"
        except asyncio.CancelledError:
            state["cancelled"] = True
            raise

    async def is_disconnected():
        state["polls"] += 1
        return state["polls"] >= 2

    async def scenario():
        started = asyncio.get_running_loop().time()
        with pytest.raises(CallerGone):
            await until_caller_leaves(provider_call(), is_disconnected, poll_seconds=0.01)
        return asyncio.get_running_loop().time() - started

    elapsed = asyncio.run(scenario())
    assert state["cancelled"], "the provider call itself was cancelled, not left running"
    assert elapsed < 5


def test_a_caller_that_stays_gets_the_answer_and_its_errors():
    async def stays():
        return False

    async def answer():
        await asyncio.sleep(0.03)
        return "the answer"

    async def failure():
        await asyncio.sleep(0.01)
        raise ValueError("provider error")

    assert asyncio.run(until_caller_leaves(answer(), stays, poll_seconds=0.01)) == "the answer"
    with pytest.raises(ValueError, match="provider error"):
        asyncio.run(until_caller_leaves(failure(), stays, poll_seconds=0.01))


def test_the_route_still_answers_a_caller_that_waits(client):
    from tests.conftest import AUTH, completion

    response = client.post("/v1/completions", json=completion(), headers=AUTH)
    assert response.status_code == 200
    assert response.json()["output"]
