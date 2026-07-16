import {render, screen} from '@testing-library/react';
import {describe, expect, it} from 'vitest';
import MyModelForm from './MyModelForm';

describe('MyModelForm', () => {
  it('回显温度时不暴露浮点存储误差', () => {
    render(
      <MyModelForm
        initial={{
          configured: true,
          baseUrl: 'https://example.com/v1',
          chatModel: 'example-model',
          temperature: 0.20000000298023224,
          maskedApiKey: '****test',
        }}
      />,
    );

    expect(screen.getByRole('spinbutton')).toHaveValue(0.2);
    expect(screen.getByRole('spinbutton')).toHaveAttribute('value', '0.2');
  });
});
