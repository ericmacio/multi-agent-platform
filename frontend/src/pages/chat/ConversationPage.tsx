import { useNavigate, useParams } from 'react-router-dom';
import { ConversationView } from '@/features/conversations/ConversationView';

/**
 * Thin wrapper around `ConversationView` that owns the routing side effects:
 * after delete → bounce to `/chat`; on "Conversation full" CTA → bounce to
 * `/chat/new?agentId=<id>`. The view itself stays router-agnostic.
 */
export default function ConversationPage(): JSX.Element {
  const navigate = useNavigate();
  const { conversationId } = useParams<{ conversationId: string }>();

  if (!conversationId) {
    return <div />;
  }

  return (
    <ConversationView
      conversationId={conversationId}
      onDeleted={() => navigate('/chat', { replace: true })}
      onStartNew={(agentId) => navigate(`/chat/new?agentId=${agentId}`)}
    />
  );
}
