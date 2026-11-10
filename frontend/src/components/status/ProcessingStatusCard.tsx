interface ProcessingStatusCardProps {
  title?: string;
  description?: string;
  variant?: 'default' | 'compact';
  label?: string;
}

/** Presentation only: callers supply a confirmed loading/processing state. */
export function ProcessingStatusCard({
  title = '正在分析材料',
  description = '系统正在处理上传文件，请稍候。',
  variant = 'default',
  label,
}: ProcessingStatusCardProps) {
  return <div className={`processing-status-card processing-status-card--${variant}`}
    role="status" aria-live="polite" aria-atomic="true" aria-label={label ?? title}>
    <span className="processing-status-card__icon" aria-hidden="true"><span className="processing-status-card__spinner" /></span>
    <div className="processing-status-card__content">
      <p className="processing-status-card__title">{title}</p>
      {description && <p className="processing-status-card__description">{description}</p>}
    </div>
  </div>;
}
