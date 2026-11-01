import { ArrowDownRight, ArrowUpRight } from 'lucide-react'

export default function StatCard({ label, value, detail, trend, tone, icon: Icon }) {
  return (
    <article className="stat-card">
      <div className="stat-card-top">
        <span className="stat-label">{label}</span>
        <span className={`stat-icon stat-icon-${tone}`}><Icon size={16} strokeWidth={1.8} /></span>
      </div>
      <div className="stat-card-bottom">
        <div>
          <div className="stat-value">{value}</div>
          <div className="stat-detail">{detail}</div>
        </div>
        <span className={`stat-trend ${trend.startsWith('-') ? 'trend-down' : 'trend-up'}`}>
          {trend.startsWith('-') ? <ArrowDownRight size={13} /> : <ArrowUpRight size={13} />}
          {trend.replace(/^[+-]/, '')}
        </span>
      </div>
    </article>
  )
}
