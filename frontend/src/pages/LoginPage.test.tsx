import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import {beforeEach, describe, expect, it} from 'vitest';
import {MemoryRouter} from 'react-router-dom';
import LoginPage from './LoginPage';

describe('LoginPage', () => {
  beforeEach(() => {
    localStorage.clear();
    document.title = '旧页面标题';
  });

  it('进入登录页和切换注册时更新浏览器标题', async () => {
    render(
      <MemoryRouter initialEntries={['/login']}>
        <LoginPage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(document.title).toBe('登录 · AI 面试平台'));
    fireEvent.click(screen.getByRole('button', {name: '注册'}));
    await waitFor(() => expect(document.title).toBe('注册 · AI 面试平台'));
  });
});
