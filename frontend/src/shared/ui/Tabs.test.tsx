import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TabContent, TabList, TabTrigger, Tabs } from './Tabs';

function fixture() {
  return (
    <Tabs defaultValue="a">
      <TabList>
        <TabTrigger value="a">A</TabTrigger>
        <TabTrigger value="b">B</TabTrigger>
        <TabTrigger value="c">C</TabTrigger>
      </TabList>
      <TabContent value="a">panel-a</TabContent>
      <TabContent value="b">panel-b</TabContent>
      <TabContent value="c">panel-c</TabContent>
    </Tabs>
  );
}

describe('Tabs', () => {
  test('initial selection renders the first panel and marks aria-selected', () => {
    render(fixture());
    const tabA = screen.getByRole('tab', { name: 'A' });
    expect(tabA).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel').textContent).toBe('panel-a');
  });

  test('clicking a tab updates the active panel', async () => {
    render(fixture());
    await userEvent.click(screen.getByRole('tab', { name: 'B' }));
    expect(screen.getByRole('tabpanel').textContent).toBe('panel-b');
    expect(screen.getByRole('tab', { name: 'B' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'A' })).toHaveAttribute('aria-selected', 'false');
  });

  test('ArrowRight / ArrowLeft cycle focus between triggers', async () => {
    render(fixture());
    const tabA = screen.getByRole('tab', { name: 'A' });
    tabA.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'B' }));
    await userEvent.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'C' }));
    await userEvent.keyboard('{ArrowRight}'); // wraps
    expect(document.activeElement).toBe(tabA);
    await userEvent.keyboard('{ArrowLeft}');
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'C' }));
  });

  test('Home / End jump to first / last', async () => {
    render(fixture());
    screen.getByRole('tab', { name: 'B' }).focus();
    await userEvent.keyboard('{End}');
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'C' }));
    await userEvent.keyboard('{Home}');
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'A' }));
  });

  test('aria-controls links each trigger to its panel id', () => {
    render(fixture());
    const tabA = screen.getByRole('tab', { name: 'A' });
    const panelId = tabA.getAttribute('aria-controls');
    expect(panelId).toBeTruthy();
    const panel = document.getElementById(panelId!);
    expect(panel).toBeInTheDocument();
    expect(panel?.textContent).toBe('panel-a');
  });
});
