import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import RecruitmentRadarPage from './RecruitmentRadarPage';

describe('RecruitmentRadarPage', () => {
  it('只展示已确认的三个招聘来源及其精确链接', () => {
    render(<RecruitmentRadarPage />);

    expect(screen.getByRole('link', { name: /OfferComing/ })).toHaveAttribute(
      'href',
      'https://offercoming.cn/',
    );
    expect(screen.getByRole('link', { name: /卡码投递表/ })).toHaveAttribute(
      'href',
      'https://toudi.kamacoder.com/',
    );
    expect(screen.getByRole('link', { name: /校招信息腾讯文档/ })).toHaveAttribute(
      'href',
      'https://docs.qq.com/smartsheet/DTkRMUVhoUWJXZEhJ?tab=tvVDZj&viewId=vmLdET',
    );
    expect(screen.queryByText(/Gank Interview/i)).not.toBeInTheDocument();
  });
});
