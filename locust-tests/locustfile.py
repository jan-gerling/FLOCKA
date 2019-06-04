from locust import HttpLocust, TaskSet, task, TaskSequence, seq_task
import requests, re, random

# Users Service
def create_user(locust_user):
    response = locust_user.client.post("/users/create")
    return re.search('\((.+?)\)', response.text).group(1)

def remove_user(locust_user):
    response = locust_user.client.delete("/users/remove/%s" %(locust_user.user_id))
    return response.text

def find_user(locust_user):
    response = locust_user.client.get("/users/find/" %(locust_user.user_id))
    return response.text

def get_credit(locust_user):
    response = locust_user.client.get("/users/credit/%s" %(locust_user.user_id))
    return re.search(',(.+?)\)', response.text).group(1)

def subtract_credit(locust_user, credit):
    response = locust_user.client.post("/users/credit/subtract/%s/%i" %(locust_user.user_id, credit))
    if response.status_code == 200:
        locust_user.credit -= credit
    return response.text

def add_credit(locust_user, credit):
    response = locust_user.client.post("/users/credit/add/%s/%i" %(locust_user.user_id, credit))
    if response.status_code == 200:
        locust_user.credit += credit
    return response.text

# Order Service
def create_order(locust_user):
    response = locust_user.client.post("/orders/create" %(locust_user.user_id))
    order_id = re.search('\((.+?)\)', response.text).group(1)
    if response.status_code == 200:
        locust_user.my_orders.append(order_id)
    return order_id

def remove_order(locust_user, order_id):
    response = locust_user.client.delete("/orders/remove/%s" %(order_id))
    if response.status_code == 200:
        try:
            locust_user.my_orders.remove(order_id)
        except ValueError:
            print('removed an order that was not mine')
    return response.text

def find_order(locust_user, order_id):
    response = locust_user.client.get("/orders/find/" %(order_id))
    return response.text

def order_add_item(locust_user, order_id, item_id):
    response = locust_user.client.post("/orders/addItem/%s/%s" %(order_id, item_id))

def order_remove_item(locust_user, order_id, item_id):
    response = locust_user.client.post("/orders/removeItem/%s/%s" %(order_id, item_id))

def checkout_order(locust_user, order_id):
    response = locust_user.client.post("/orders/checkout/%s" %(order_id))
    if response.status_code == 200:
        try:
            locust_user.my_orders.remove(order_id)
            #we also have to substract credit
        except ValueError:
            print('removed an order that was not mine')
    return response.text

# Stock Service
def stock_availability(locust_user, item_id):
    return locust_user.client.get("/stock/availability/%s" %(item_id))
    
def add_stock(locust_user, item_id, quantity):
    return locust_user.client.post("/stock/add/%s/%s" %(item_id, quantity))

def subtract_stock(locust_user, item_id, quantity):
    return locust_user.client.post("/stock/subtract/%s/%s" %(item_id, quantity))

def create_item(locust_user):
    response = locust_user.client.post("/stock/item/create/")
    if response.status_code == 200:
        return re.search('\((.+?)\)', response.text).group(1)
    else:
        return None

# aux functions
def populate_items(locust_user):
    items_created = []
    for i in range(random.randint(5, 10)):
        new_item = create_item(locust_user)
        if new_item is not None:
            add_stock(locust_user, new_item, 1000)
            items_created.append(new_item)
    return items_created


class UserBehavior(TaskSet):
    def on_start(self):
        """ on_start is called when a Locust start before any task is scheduled """
        
        self.user_id = create_user(self)
        self.credit = 0
        self.my_orders = []
        self.items_available = populate_items(self)
        add_credit(self, random.randint(1000, 5000))

    def on_stop(self):
        print(self.credit, get_credit(self))

    @task
    def add(self):
        credit = random.randint(1, 10)
        add_credit(self, credit)

    @task
    def sub(self):
        credit = random.randint(1, 10)
        subtract_credit(self, credit)
    '''
    @task
    class make_order(TaskSequence):
        def __init__(self, superTask):
            self.superTask = superTask

        @seq_task(1)
        def create_new_order(self):
            self.actual_order = create_order(self.superTask)

        @seq_task(2)
        @task(random.randint(1, 5))
        def populate_order(self):
            item = random.choice(self.superTask.items_available)
            order_add_item(self, self.actual_order, item)

        @seq_task(3)
        def checkout(self):
            checkout_order(self, self.actual_order)
    '''
class WebsiteUser(HttpLocust):
    task_set = UserBehavior
    min_wait = 500
    max_wait = 900