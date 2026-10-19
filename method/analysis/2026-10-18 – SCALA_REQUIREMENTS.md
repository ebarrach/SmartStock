# SmartStock — Scala Agent Requirements

## Purpose

Scala is used in SmartStock to implement the **SmartStock Agent**.

The agent acts as an intermediary ("passe-plat") between the different components of the SmartStock system.

Its role is to receive information and events, interpret or route them, handle asynchronous communication and transmit the appropriate information to the relevant SmartStock component.

The Scala implementation must therefore satisfy two objectives:

1. Implement the SmartStock Agent and its communication logic.
2. Demonstrate the Scala concepts studied during the course.

---

## SmartStock Agent Responsibilities

The Scala Agent should be able to:

- Receive SmartStock events.
- Receive information coming from the cabinet/system.
- Represent received information using Scala domain objects.
- Process or classify incoming events.
- Route information to the appropriate component.
- Handle asynchronous operations.
- Handle failures and unexpected values.
- Exchange messages between actors when the Actor model is used.
- Forward the resulting information to the rest of SmartStock.

The agent should remain focused on communication, event processing and coordination.

It should not unnecessarily contain responsibilities belonging to the mobile application or hardware layer.

---

# Scala Concepts to Demonstrate

## 1. Case Classes

Use case classes to represent messages, events and data handled by the agent.

Examples:

```scala
case class MedicationDetected(dataMatrix: String)
case class StockUpdated(dataMatrix: String, quantity: Int)
case class SensorReading(sensorId: String, value: Double)
case class SensorFailure(sensorId: String)
```

## 2. Traits / Inheritance

Use traits or inheritance to define common categories of messages or events.

```scala
trait SmartStockEvent

case class MedicationDetected(dataMatrix: String) extends SmartStockEvent
case class SensorFailure(sensorId: String) extends SmartStockEvent
```

## 3. Pattern Matching

Use pattern matching to determine how the agent reacts to received events.

```scala
event match {
  case MedicationDetected(code) =>
    // Process medication detection
  case SensorFailure(sensorId) =>
    // Process sensor failure
  case _ =>
    // Handle unknown event
}
```

## 4. Immutable Collections

Use immutable Scala collections where the agent needs to manipulate collections of events, messages or information.

Examples:
- Received events
- Sensor information
- Pending messages
- Processing results

## 5. Option

Use `Option` when information received by the agent may be absent.

Examples:
- Missing DataMatrix information
- Unknown medication
- Missing sensor information

The agent must correctly handle `Some(...)` and `None`.

## 6. Try

Use `Try` for operations that may fail.

Examples:
- Parsing received information
- Converting sensor values
- Processing malformed messages
- Communication operations

## 7. For-Comprehensions

Use for-comprehensions when several operations need to be composed.

They may be used with:
- Option
- Try
- Collections
- Futures

## 8. Futures / Asynchronous Processing

The agent should demonstrate asynchronous processing where appropriate.

Possible examples:
- Receiving information
- Processing an event
- Waiting for a response
- Forwarding information
- Communicating with another SmartStock component

The agent should avoid unnecessary blocking operations.

## 9. Actor Model

The Actor model can be used to structure the SmartStock Agent.

Actors can receive and exchange messages representing SmartStock events.

```text
SmartStock component
        ↓
   Scala Agent
        ↓
   Actor / message
        ↓
 Event processing
        ↓
 Appropriate destination
```

The exact actor architecture will be determined during implementation.

## 10. Failure and Latency Handling

The agent must not assume that every communication succeeds immediately.

It should be able to deal with:
- Missing information
- Invalid information
- Processing failures
- Communication failures
- Delayed responses
- Asynchronous operations

---

# Elements to Find in the Final Scala Agent

Before the evaluation, the following elements should be identifiable in the Scala source code:

- [ ] Case classes representing messages/events
- [ ] Traits and/or inheritance
- [ ] Pattern matching
- [ ] Immutable collections
- [ ] Option
- [ ] Try
- [ ] For-comprehensions
- [ ] Futures / asynchronous processing
- [ ] Actor model
- [ ] Actor messages
- [ ] Failure handling
- [ ] Latency/asynchronous behaviour

Each element used must have a real purpose inside the SmartStock Agent.

---

# Evaluation

For every Scala mechanism used, the team should be able to show:

1. Where it is located in the SmartStock Agent.
2. What it does.
3. Why this Scala mechanism was selected.
4. How it contributes to the agent.
5. How errors or unexpected situations are handled.

The objective is not to artificially add Scala features. The Scala concepts should contribute to the implementation of the SmartStock Agent.

---

# Scala Evaluation

Expected period: **January 2027**

Exact date: **TO BE CONFIRMED**
