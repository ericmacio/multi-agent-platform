import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { Dropdown, DropdownContent, DropdownItem, DropdownTrigger } from './Dropdown';
import { Modal } from './Modal';
import { Button } from './Button';

function Composed() {
  const [modalOpen, setModalOpen] = useState(false);
  return (
    <>
      <Dropdown>
        <DropdownTrigger>menu</DropdownTrigger>
        <DropdownContent>
          <DropdownItem onClick={() => setModalOpen(true)}>open modal</DropdownItem>
          <DropdownItem>other</DropdownItem>
        </DropdownContent>
      </Dropdown>
      <Modal open={modalOpen} onOpenChange={setModalOpen} title="Confirm">
        <Button>Cancel</Button>
        <Button>Ok</Button>
      </Modal>
    </>
  );
}

describe('overlay composition', () => {
  test('clicking a dropdown item that opens a modal closes the dropdown and opens the modal', async () => {
    render(<Composed />);
    await userEvent.click(screen.getByRole('button', { name: 'menu' }));
    expect(screen.getByRole('menu')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('menuitem', { name: 'open modal' }));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  test('Esc inside an open modal closes the modal (Dropdown is already closed)', async () => {
    render(<Composed />);
    await userEvent.click(screen.getByRole('button', { name: 'menu' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'open modal' }));
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
