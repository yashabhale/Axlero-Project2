export default function App() {
  return (
    <div className="planning-page">
      <div className="plan-grid" aria-label="Project roadmap">
        <div className="plan-card plan-card--label">
          <span>Week</span>
        </div>

        <div className="plan-card plan-card--feature">
          <h2>
            Data Ingestion &amp; Indexing (Java, Lucene,
            <br />
            gRPC)
          </h2>
        </div>

        <div className="plan-card plan-card--task plan-card--task-left">
          <div className="task-label">Week 1</div>
          <p>
            Ingestion Setup: Define the protobuf
            <br />
            schema for a Log Message. Build a Java
            <br />
            gRPC server to receive mock logs
            <br />
            efficiently.
          </p>
        </div>

        <div className="plan-card plan-card--task plan-card--task-right">
          <p>
            Dashboard Scaffolding: Build the
            <br />
            React UI. Create the main search bar
            <br />
            and the layout for log results.
          </p>
        </div>
      </div>

      <div className="watermark" aria-hidden="true">
        SOLUTION
      </div>
    </div>
  )
}
