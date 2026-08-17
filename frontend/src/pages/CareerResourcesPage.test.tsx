import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import CareerResourcesPage from './CareerResourcesPage';

describe('CareerResourcesPage', () => {
  it('展示常用外部资源和平台内练习入口', () => {
    render(
      <MemoryRouter>
        <CareerResourcesPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: /力扣中国/ })).toHaveAttribute('href', 'https://leetcode.cn/');
    expect(screen.getByRole('link', { name: /代码随想录/ })).toHaveAttribute('href', 'https://programmercarl.com/');
    expect(screen.getByRole('link', { name: /JavaGuide/ })).toHaveAttribute('href', 'https://javaguide.cn/');
    expect(screen.getByRole('link', { name: /面渣逆袭/ })).toHaveAttribute(
      'href',
      'https://javabetter.cn/sidebar/sanfene/nixi.html',
    );
    expect(screen.getByRole('link', { name: /模拟面试/ })).toHaveAttribute('href', '/interview');
  });
});
