-- Insert sample users with weak passwords
INSERT INTO users (username, password, email, role) VALUES 
('admin', 'admin123', 'admin@vulnerable.com', 'ADMIN'),
('user1', 'password', 'user1@vulnerable.com', 'USER'),
('john', '123456', 'john@vulnerable.com', 'USER'),
('alice', 'qwerty', 'alice@vulnerable.com', 'USER'),
('bob', 'password123', 'bob@vulnerable.com', 'MANAGER'),
('test', 'test', 'test@vulnerable.com', 'USER'); 