import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation,
} from 'react-router-dom';
import RequireAuth from './RequireAuth';
import LoginPage from '../pages/LoginPage';
import { ACCESS_TOKEN_KEY } from '../api/authStorage';

function LoginLocation() {
  const location = useLocation();
  const from = new URLSearchParams(location.search).get('from');
  return <div data-testid="login-location">{`${location.pathname}|${from}`}</div>;
}

function renderGuard(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/login" element={<LoginLocation />} />
        <Route element={<RequireAuth />}>
          <Route path="/job-practice" element={<div>岗位实战内容</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('RequireAuth', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('未登录时在业务页渲染前跳转登录并保留站内回跳地址', async () => {
    renderGuard('/job-practice?stage=github#evidence');

    expect(await screen.findByTestId('login-location')).toHaveTextContent(
      '/login|/job-practice?stage=github#evidence',
    );
    expect(screen.queryByText('岗位实战内容')).not.toBeInTheDocument();
  });

  it('存在非空本地 access token 时放行业务路由', () => {
    window.localStorage.setItem(ACCESS_TOKEN_KEY, 'local-access-token');

    renderGuard('/job-practice');

    expect(screen.getByText('岗位实战内容')).toBeInTheDocument();
    expect(screen.queryByTestId('login-location')).not.toBeInTheDocument();
  });

  it('已登录访问恶意外部回跳地址时只进入安全的站内默认页', async () => {
    window.localStorage.setItem(ACCESS_TOKEN_KEY, 'local-access-token');

    render(
      <MemoryRouter initialEntries={['/login?from=https%3A%2F%2Fevil.example%2Fsteal']}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/dashboard" element={<div>安全首页</div>} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText('安全首页')).toBeInTheDocument();
  });
});
