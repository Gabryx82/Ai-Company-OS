import { Component, type ErrorInfo, type ReactNode } from "react";

/**
 * A view that throws while rendering shows this, and only this view goes: the
 * shell, the navigation and every other page keep working. Before it existed a
 * single null in one response (the Knowledge Hub, PHASE 15) blanked the whole
 * console.
 */
export class ErrorBoundary extends Component<{ children: ReactNode; label: string }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(`La vista "${this.props.label}" si è interrotta`, error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="notice notice-danger" role="alert">
        <strong>La vista «{this.props.label}» si è interrotta.</strong>
        <div>Il resto della console funziona. Dettaglio tecnico: <code>{this.state.error.message}</code></div>
        <div className="row" style={{ marginTop: 8 }}>
          <button className="btn btn-small" onClick={() => this.setState({ error: null })}>Riprova</button>
        </div>
      </div>
    );
  }
}
