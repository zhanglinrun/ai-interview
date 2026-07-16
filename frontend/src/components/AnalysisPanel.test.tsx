import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AnalysisPanel from './AnalysisPanel';
import type { AnalysisItem } from '../api/history';

vi.mock('./RadarChart', () => ({
  default: () => <div data-testid="radar-chart" />,
}));

vi.mock('./ScoreProgressBar', () => ({
  default: () => <div data-testid="score-progress" />,
}));

describe('AnalysisPanel', () => {
  it('合法的低分分析仍展示结果，不误判为分析失败', () => {
    const analysis: AnalysisItem = {
      id: 1,
      overallScore: 5,
      contentScore: 1,
      structureScore: 1,
      skillMatchScore: 1,
      expressionScore: 1,
      projectScore: 1,
      summary: '项目描述还需要补充具体职责和结果。',
      analyzedAt: '2026-07-15T10:00:00',
      strengths: [],
      suggestions: [],
    };

    render(
      <AnalysisPanel
        analysis={analysis}
        analyzeStatus="COMPLETED"
        onExport={vi.fn()}
        exporting={false}
      />,
    );

    expect(screen.getByText('项目描述还需要补充具体职责和结果。')).toBeInTheDocument();
    expect(screen.queryByText('分析失败')).not.toBeInTheDocument();
  });
});
